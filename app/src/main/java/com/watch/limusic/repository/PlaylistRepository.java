package com.watch.limusic.repository;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.watch.limusic.api.NavidromeApi;
import com.watch.limusic.database.MusicDatabase;
import com.watch.limusic.database.PlaylistDao;
import com.watch.limusic.database.PlaylistEntity;
import com.watch.limusic.database.PlaylistSongDao;
import com.watch.limusic.database.PlaylistSongEntity;
import com.watch.limusic.database.SongEntity;
import com.watch.limusic.model.Song;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌单仓库（Navidrome 专用）。
 */
public class PlaylistRepository {
	private static final String TAG = "PlaylistRepository";
	private static volatile PlaylistRepository INSTANCE;

	public static PlaylistRepository getInstance(Context context) {
		if (INSTANCE == null) {
			synchronized (PlaylistRepository.class) {
				if (INSTANCE == null) INSTANCE = new PlaylistRepository(context);
			}
		}
		return INSTANCE;
	}

	private final Context appContext;
	private final MusicDatabase db;
	private final PlaylistDao playlistDao;
	private final PlaylistSongDao playlistSongDao;
	private final NavidromeApi api;

	private PlaylistRepository(Context context) {
		this.appContext = context.getApplicationContext();
		this.db = MusicDatabase.getInstance(appContext);
		this.playlistDao = db.playlistDao();
		this.playlistSongDao = db.playlistSongDao();
		this.api = NavidromeApi.getInstance(appContext);
	}

	public interface AddCallback {
		void onResult(List<String> skippedTitles, boolean serverOk);
	}

	// 创建歌单（允许重名）
	public PlaylistEntity createPlaylist(String name, boolean isPublic) throws Exception {
		if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("歌单名不能为空");
		PlaylistEntity entity = new PlaylistEntity(name.trim(), isPublic);
		entity.setDeleted(false);
		entity.setSyncDirty(true);
		long localId = playlistDao.insert(entity);
		entity.setLocalId(localId);
		new Thread(() -> {
			try {
				boolean ok = api.createPlaylist(name.trim(), isPublic);
				if (ok) {
					try { syncPlaylistsHeader(); } catch (Exception ignore) {}
					sendPlaylistsUpdatedBroadcast();
				}
			} catch (IOException e) {
				Log.w(TAG, "服务器创建歌单异常: " + e.getMessage());
			}
		}).start();
		return entity;
	}

	// 新增：确保根据 serverId 存在对应本地歌单并返回 localId（若不存在则创建并回填基础字段）
	public long ensureLocalFromRemoteHeader(String serverId, String name, boolean isPublic, int songCount, long changedAt) {
		if (serverId == null || serverId.isEmpty()) return -1L;
		PlaylistEntity exist = playlistDao.getByServerId(serverId);
		if (exist != null) {
			// 对齐基础信息
			exist.setName(name != null ? name : exist.getName());
			exist.setPublic(isPublic);
			exist.setSongCount(songCount);
			exist.setChangedAt(changedAt > 0 ? changedAt : exist.getChangedAt());
			playlistDao.update(exist);
			return exist.getLocalId();
		}
		// 创建
		PlaylistEntity add = new PlaylistEntity(name != null ? name : "", isPublic);
		add.setServerId(serverId);
		add.setSongCount(songCount);
		add.setChangedAt(changedAt > 0 ? changedAt : System.currentTimeMillis());
		add.setOwner("");
		long id = playlistDao.insert(add);
		add.setLocalId(id);
		return id;
	}

	public void syncPlaylistsHeader() throws IOException {
		NavidromeApi.PlaylistsEnvelope env = api.getPlaylists();
		if (env == null || env.getResponse() == null || env.getResponse().getPlaylists() == null) return;
		List<NavidromeApi.Playlist> remote = env.getResponse().getPlaylists().getList();
		if (remote == null) remote = new ArrayList<>();
		// 建立服务端现有ID集合
		java.util.HashSet<String> serverIds = new java.util.HashSet<>();
		for (NavidromeApi.Playlist rp : remote) { serverIds.add(rp.getId()); }
		// 清理本地：凡是本地存在 serverId 但服务端不存在的，标记软删除；
		// 对于本地新建但尚未同步（serverId 为空）的记录，进一步清理“无歌曲关联”的本地孤儿项
		List<PlaylistEntity> locals = playlistDao.getAllIncludingDeleted();
		for (PlaylistEntity pl : locals) {
			String sid = pl.getServerId();
			if (sid == null || sid.isEmpty()) continue; // 本地新建待同步，跳过软删
			if (!serverIds.contains(sid)) {
				playlistDao.softDelete(pl.getLocalId(), System.currentTimeMillis());
			}
		}
		// 二次清理：删除无服务端ID且无歌曲关联的本地孤儿
		try { playlistDao.deleteOrphanLocalPlaylists(); } catch (Exception ignore) {}
		// 对齐/新增现存项
		for (NavidromeApi.Playlist rp : remote) {
			PlaylistEntity exist = playlistDao.getByServerId(rp.getId());
			if (exist == null) {
				String name = ensureUniqueName(rp.getName());
				PlaylistEntity add = new PlaylistEntity(name, rp.isPublic());
				add.setServerId(rp.getId());
				add.setSongCount(rp.getSongCount());
				add.setChangedAt(rp.getChanged());
				add.setOwner(rp.getOwner());
				add.setSyncDirty(false);
				long id = playlistDao.insert(add);
				add.setLocalId(id);
			} else {
				exist.setName(rp.getName());
				exist.setPublic(rp.isPublic());
				exist.setSongCount(rp.getSongCount());
				exist.setChangedAt(rp.getChanged());
				exist.setOwner(rp.getOwner());
				exist.setSyncDirty(false);
				playlistDao.update(exist);
			}
		}
	}

	private String ensureUniqueName(String base) {
		// 允许重名：不再改名加后缀，直接返回原名，由 UI 和 serverId 进行区分
		return base;
	}

	public void addSongsAtHeadFiltered(long playlistLocalId, List<String> orderedSongIds, AddCallback callback) {
		if (orderedSongIds == null || orderedSongIds.isEmpty()) {
			if (callback != null) callback.onResult(new ArrayList<>(), true);
			return;
		}
		// 计算重复
		List<String> existing = playlistSongDao.getExistingIds(playlistLocalId, orderedSongIds);
		List<String> toAdd = new ArrayList<>();
		for (String id : orderedSongIds) if (!existing.contains(id)) toAdd.add(id);
		List<String> skippedTitles = new ArrayList<>();
		if (existing != null && !existing.isEmpty()) {
			skippedTitles = db.songDao().getTitlesByIds(existing);
		}
		if (toAdd.isEmpty()) {
			if (callback != null) callback.onResult(skippedTitles, true);
			return;
		}
		// 本地插入：按尾部追加，保持“先添加的在前面”
		playlistSongDao.insertAtTailKeepingOrder(playlistLocalId, toAdd);
		int count = playlistSongDao.getCount(playlistLocalId);
		playlistDao.updateStats(playlistLocalId, count, System.currentTimeMillis(), true);
		sendPlaylistChangedBroadcast(playlistLocalId);
		// 后台同步
		final List<String> skippedFinal = new ArrayList<>(skippedTitles);
		final int countFinal = count;
		final List<String> toAddFinal = new ArrayList<>(toAdd);
		new Thread(() -> {
			boolean ok = true;
			try {
				PlaylistEntity p = playlistDao.getByLocalId(playlistLocalId);
				if (p != null && p.getServerId() != null && !p.getServerId().isEmpty()) {
					ok = api.updatePlaylist(p.getServerId(), null, null, toAddFinal, null);
					if (ok) playlistDao.updateStats(playlistLocalId, countFinal, System.currentTimeMillis(), false);
				}
			} catch (Exception e) {
				Log.w(TAG, "添加歌曲服务器同步失败: " + e.getMessage());
				ok = false;
			}
			if (callback != null) callback.onResult(skippedFinal, ok);
		}).start();
	}

	public List<Song> getSongsInPlaylist(long playlistLocalId, int limit, int offset) {
		List<SongEntity> entities = db.playlistSongDao().getSongsByPlaylist(playlistLocalId, limit, offset);
		return com.watch.limusic.database.EntityConverter.toSongs(entities);
	}

	public void removeByOrdinals(long playlistLocalId, List<Integer> ordinals, Map<String, Integer> serverIndexBySongId) {
		if (ordinals == null || ordinals.isEmpty()) return;
		playlistSongDao.deleteByOrdinals(playlistLocalId, ordinals);
		int count = playlistSongDao.getCount(playlistLocalId);
		playlistDao.updateStats(playlistLocalId, count, System.currentTimeMillis(), true);
		sendPlaylistChangedBroadcast(playlistLocalId);
		new Thread(() -> {
			try {
				PlaylistEntity p = playlistDao.getByLocalId(playlistLocalId);
				if (p != null && p.getServerId() != null && !p.getServerId().isEmpty() && serverIndexBySongId != null) {
					List<Integer> serverIdx = new ArrayList<>(serverIndexBySongId.values());
					boolean ok = api.updatePlaylist(p.getServerId(), null, null, null, serverIdx);
					if (ok) playlistDao.updateStats(playlistLocalId, count, System.currentTimeMillis(), false);
				}
			} catch (Exception e) {
				Log.w(TAG, "移除歌曲服务器同步失败: " + e.getMessage());
			}
		}).start();
	}

	public void reorder(long playlistLocalId, int from, int to) {
		playlistSongDao.reorder(playlistLocalId, from, to);
		playlistDao.updateStats(playlistLocalId, playlistSongDao.getCount(playlistLocalId), System.currentTimeMillis(), true);
		sendPlaylistChangedBroadcast(playlistLocalId);
	}

	public boolean rename(long playlistLocalId, String newName) throws Exception {
		if (newName == null || newName.trim().isEmpty()) throw new IllegalArgumentException("新名称不能为空");
		int rows = playlistDao.rename(playlistLocalId, newName.trim(), System.currentTimeMillis());
		sendPlaylistsUpdatedBroadcast();
		new Thread(() -> {
			try {
				PlaylistEntity p = playlistDao.getByLocalId(playlistLocalId);
				if (p != null && p.getServerId() != null && !p.getServerId().isEmpty()) {
					boolean ok = api.updatePlaylist(p.getServerId(), newName.trim(), null, null, null);
					if (ok) {
						try { syncPlaylistsHeader(); } catch (Exception ignore) {}
						sendPlaylistsUpdatedBroadcast();
					}
				}
			} catch (Exception e) { Log.w(TAG, "重命名服务器同步失败: " + e.getMessage()); }
		}).start();
		return rows > 0;
	}

	public void setPublic(long playlistLocalId, boolean isPublic) {
		playlistDao.setPublic(playlistLocalId, isPublic, System.currentTimeMillis());
		sendPlaylistsUpdatedBroadcast();
		new Thread(() -> {
			try {
				PlaylistEntity p = playlistDao.getByLocalId(playlistLocalId);
				if (p != null && p.getServerId() != null && !p.getServerId().isEmpty()) {
					api.updatePlaylist(p.getServerId(), null, isPublic, null, null);
				}
			} catch (Exception e) { Log.w(TAG, "设置公开服务器同步失败: " + e.getMessage()); }
		}).start();
	}

	public void delete(long playlistLocalId) {
		PlaylistEntity p = playlistDao.getByLocalId(playlistLocalId);
		playlistDao.softDelete(playlistLocalId, System.currentTimeMillis());
		sendPlaylistsUpdatedBroadcast();
		new Thread(() -> {
			try {
				if (p != null && p.getServerId() != null && !p.getServerId().isEmpty()) {
					boolean ok = api.deletePlaylist(p.getServerId());
					if (!ok) Log.w(TAG, "服务器删除歌单失败");
					// 无论成功失败，都再触发一次对账以避免脏显
					try { syncPlaylistsHeader(); } catch (Exception ignore) {}
					sendPlaylistsUpdatedBroadcast();
				}
			} catch (Exception e) { Log.w(TAG, "删除歌单服务器同步失败: " + e.getMessage()); }
		}).start();
	}

	public boolean validateAndMaybeRefreshFromServer(long playlistLocalId) {
		try {
			PlaylistEntity p = playlistDao.getByLocalId(playlistLocalId);
			if (p == null || p.getServerId() == null || p.getServerId().isEmpty()) return false;
			int localCount = playlistSongDao.getCount(playlistLocalId);
			// 若本地明细为空，直接拉取详情并对齐
			if (localCount == 0) {
				NavidromeApi.PlaylistEnvelope detail = api.getPlaylist(p.getServerId());
				NavidromeApi.RemotePlaylist remote = detail != null && detail.getResponse() != null ? detail.getResponse().getPlaylist() : null;
				if (remote != null) {
					try {
						List<Song> entries = remote.getEntries();
						if (entries != null && !entries.isEmpty()) {
							List<com.watch.limusic.database.AlbumEntity> placeholders = new ArrayList<>();
							for (Song s : entries) {
								String albumId = s.getAlbumId();
								if (albumId == null || albumId.isEmpty()) continue;
								com.watch.limusic.database.AlbumEntity existAlbum = db.albumDao().getAlbumById(albumId);
								if (existAlbum == null) {
									String name = s.getAlbum() != null ? s.getAlbum() : "";
									String artist = s.getArtist() != null ? s.getArtist() : "";
									String cover = s.getCoverArtUrl();
									placeholders.add(new com.watch.limusic.database.AlbumEntity(albumId, name, artist, "", cover, 0, 0, 0));
								}
							}
							if (!placeholders.isEmpty()) db.albumDao().insertAlbumsIfAbsent(placeholders);
							List<SongEntity> toInsert = com.watch.limusic.database.EntityConverter.toSongEntities(entries);
							db.songDao().insertAllSongs(toInsert);
						}
					} catch (Exception eIgnore) { Log.w(TAG, "回填歌单歌曲到本地失败(忽略): " + eIgnore.getMessage()); }
					playlistSongDao.deleteAllForPlaylist(playlistLocalId);
					List<Song> entries = remote.getEntries();
					long now = System.currentTimeMillis();
					List<PlaylistSongEntity> items = new ArrayList<>();
					for (int i = 0; i < entries.size(); i++) {
						items.add(new PlaylistSongEntity(playlistLocalId, entries.get(i).getId(), i, now));
					}
					if (!items.isEmpty()) playlistSongDao.insertAll(items);
					playlistDao.updateStats(playlistLocalId, items.size(), remote.getChanged(), false);
					sendPlaylistChangedBroadcast(playlistLocalId);
					return true;
				}
			}
			// 正常路径：根据 changed 判定是否需要刷新
			NavidromeApi.PlaylistsEnvelope env = api.getPlaylists();
			NavidromeApi.PlaylistsResponse r = env != null ? env.getResponse() : null;
			List<NavidromeApi.Playlist> list = r != null && r.getPlaylists() != null ? r.getPlaylists().getList() : new ArrayList<>();
			for (NavidromeApi.Playlist rp : list) {
				if (p.getServerId().equals(rp.getId())) {
					if (rp.getChanged() == p.getChangedAt()) return false;
					NavidromeApi.PlaylistEnvelope detail = api.getPlaylist(p.getServerId());
					NavidromeApi.RemotePlaylist remote = detail != null && detail.getResponse() != null ? detail.getResponse().getPlaylist() : null;
					if (remote != null) {
						// 先将服务端返回的歌曲写入本地 songs 表，避免 join 为空
						try {
							List<Song> entries = remote.getEntries();
							if (entries != null && !entries.isEmpty()) {
								// 专辑占位
								List<com.watch.limusic.database.AlbumEntity> placeholders = new ArrayList<>();
								for (Song s : entries) {
									String albumId = s.getAlbumId();
									if (albumId == null || albumId.isEmpty()) continue;
									com.watch.limusic.database.AlbumEntity existAlbum = db.albumDao().getAlbumById(albumId);
									if (existAlbum == null) {
										String name = s.getAlbum() != null ? s.getAlbum() : "";
										String artist = s.getArtist() != null ? s.getArtist() : "";
										String cover = s.getCoverArtUrl();
										placeholders.add(new com.watch.limusic.database.AlbumEntity(albumId, name, artist, "", cover, 0, 0, 0));
									}
								}
								if (!placeholders.isEmpty()) db.albumDao().insertAlbumsIfAbsent(placeholders);
								// 插入歌曲
								List<SongEntity> toInsert = com.watch.limusic.database.EntityConverter.toSongEntities(entries);
								db.songDao().insertAllSongs(toInsert);
							}
						} catch (Exception eIgnore) { Log.w(TAG, "回填歌单歌曲到本地失败(忽略): " + eIgnore.getMessage()); }

						// 对齐歌单明细
						playlistSongDao.deleteAllForPlaylist(playlistLocalId);
						List<Song> entries = remote.getEntries();
						long now = System.currentTimeMillis();
						List<PlaylistSongEntity> items = new ArrayList<>();
						for (int i = 0; i < entries.size(); i++) {
							items.add(new PlaylistSongEntity(playlistLocalId, entries.get(i).getId(), i, now));
						}
						if (!items.isEmpty()) playlistSongDao.insertAll(items);
						playlistDao.updateStats(playlistLocalId, items.size(), remote.getChanged(), false);
						sendPlaylistChangedBroadcast(playlistLocalId);
						return true;
					}
				}
			}
		} catch (Exception e) {
			Log.w(TAG, "校验或刷新歌单失败: " + e.getMessage());
		}
		return false;
	}

	private void sendPlaylistsUpdatedBroadcast() {
		Intent intent = new Intent("com.watch.limusic.PLAYLISTS_UPDATED");
		appContext.sendBroadcast(intent);
	}
	private void sendPlaylistChangedBroadcast(long playlistLocalId) {
		Intent intent = new Intent("com.watch.limusic.PLAYLIST_CHANGED");
		intent.putExtra("playlistLocalId", playlistLocalId);
		appContext.sendBroadcast(intent);
	}
} 