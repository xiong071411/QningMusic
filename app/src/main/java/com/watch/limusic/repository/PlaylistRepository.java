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
				// 远端存在则强制取消软删除，确保可见
				exist.setDeleted(false);
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
				if (p != null && p.getServerId() != null && !p.getServerId().isEmpty()) {
					// 重新获取服务端顺序，按 songId 精确映射出要删除的索引
					List<Integer> serverIdx = new ArrayList<>();
					try {
						NavidromeApi.PlaylistEnvelope env = api.getPlaylist(p.getServerId());
						if (env != null && env.getResponse() != null && env.getResponse().getPlaylist() != null) {
							List<com.watch.limusic.model.Song> entries = env.getResponse().getPlaylist().getEntries();
							// 从本地被删的 ordinal 反查删哪些 songId
							List<String> localOrderIds = playlistSongDao.getSongIdsOrdered(playlistLocalId);
							java.util.HashSet<String> removedIds = new java.util.HashSet<>();
							// 这里的 ordinals 来自删除前的本地快照，已删除后 localOrderIds 少了对应项，故改为在调用前就收集 ids；
							// 如果上层已传入 serverIndexBySongId（按 songId 映射），优先用它。
							if (serverIndexBySongId != null && !serverIndexBySongId.isEmpty()) {
								for (Map.Entry<String,Integer> e : serverIndexBySongId.entrySet()) {
									String sid = e.getKey();
									// 找到 sid 在服务端 entries 中的位置
									for (int i = 0; i < entries.size(); i++) {
										if (sid.equals(entries.get(i).getId())) { serverIdx.add(i); break; }
									}
								}
							} else {
								// 无映射则放弃服务端删除（避免误删），由后续对账修正
							}
						}
					} catch (Exception ex) {
						Log.w(TAG, "获取服务端歌单详情失败，跳过服务端删除: " + ex.getMessage());
					}
					if (!serverIdx.isEmpty()) {
					boolean ok = api.updatePlaylist(p.getServerId(), null, null, null, serverIdx);
					if (ok) playlistDao.updateStats(playlistLocalId, count, System.currentTimeMillis(), false);
					}
				}
			} catch (Exception e) {
				Log.w(TAG, "移除歌曲服务器同步失败: " + e.getMessage());
			}
		}).start();
	}

	public void reorder(long playlistLocalId, int fromIndex, int toIndex) {
		// 将适配器位置映射为真实 ordinal，避免因删除/合并导致的 ordinal 空洞引发错排
		List<Integer> ordinals = playlistSongDao.getOrdinalsOrdered(playlistLocalId);
		if (ordinals == null) return;
		if (fromIndex < 0 || toIndex < 0 || fromIndex >= ordinals.size() || toIndex >= ordinals.size()) return;
		int fromOrdinal = ordinals.get(fromIndex);
		int toOrdinal = ordinals.get(toIndex);
		if (fromOrdinal == toOrdinal) return;
		playlistSongDao.reorder(playlistLocalId, fromOrdinal, toOrdinal);
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

			// 1) 拉取服务器歌单头部变化，用于判断是否需要明细刷新
			NavidromeApi.PlaylistsEnvelope env = api.getPlaylists();
			NavidromeApi.PlaylistsResponse r = env != null ? env.getResponse() : null;
			java.util.List<NavidromeApi.Playlist> list = r != null && r.getPlaylists() != null ? r.getPlaylists().getList() : new java.util.ArrayList<>();
			NavidromeApi.Playlist head = null;
			for (NavidromeApi.Playlist rp : list) {
				if (p.getServerId().equals(rp.getId())) { head = rp; break; }
			}
			if (head == null) return false;

			// 如果本地已有明细（localCount>0），我们采用“本地排序优先 + 增量合并”的策略：
			// - 不覆盖本地已有曲目的相对顺序
			// - 服务端有但本地没有的曲目，按服务端返回顺序追加到尾部
			// - 服务端已删除的曲目，从本地移除
			NavidromeApi.PlaylistEnvelope detail = api.getPlaylist(p.getServerId());
			NavidromeApi.RemotePlaylist remote = detail != null && detail.getResponse() != null ? detail.getResponse().getPlaylist() : null;
			if (remote == null) return false;

			java.util.List<Song> remoteEntries = remote.getEntries() != null ? remote.getEntries() : new java.util.ArrayList<>();

			// 回填缺失的歌曲元数据，避免 JOIN 为空
			try {
				if (!remoteEntries.isEmpty()) {
					java.util.List<com.watch.limusic.database.AlbumEntity> placeholders = new java.util.ArrayList<>();
					for (Song s : remoteEntries) {
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
					java.util.List<SongEntity> toInsert = com.watch.limusic.database.EntityConverter.toSongEntities(remoteEntries);
					db.songDao().insertAllSongs(toInsert);
				}
			} catch (Exception ignore) {}

			java.util.List<String> localOrderedIds = playlistSongDao.getSongIdsOrdered(playlistLocalId);
			java.util.LinkedHashSet<String> localSet = new java.util.LinkedHashSet<>(localOrderedIds);
			java.util.ArrayList<String> remoteIds = new java.util.ArrayList<>();
			for (Song s : remoteEntries) remoteIds.add(s.getId());
			java.util.LinkedHashSet<String> remoteSet = new java.util.LinkedHashSet<>(remoteIds);

			// 需要移除的本地曲目：本地有而远端没有（按真实 ordinal 删除，避免有空洞时 i != ordinal）
			java.util.ArrayList<String> idsToRemove = new java.util.ArrayList<>();
			for (String id : localOrderedIds) {
				if (!remoteSet.contains(id)) idsToRemove.add(id);
			}
			java.util.List<Integer> ordinalsToRemove = java.util.Collections.emptyList();
			if (!idsToRemove.isEmpty()) {
				ordinalsToRemove = playlistSongDao.getOrdinalsForSongIds(playlistLocalId, idsToRemove);
				if (ordinalsToRemove != null && !ordinalsToRemove.isEmpty()) {
					playlistSongDao.deleteByOrdinals(playlistLocalId, ordinalsToRemove);
				}
			}

			// 需要追加的远端曲目：远端有而本地没有（保持服务端顺序，尾部追加）
			java.util.ArrayList<String> toAppend = new java.util.ArrayList<>();
			for (String rid : remoteIds) if (!localSet.contains(rid)) toAppend.add(rid);
			if (!toAppend.isEmpty()) {
				playlistSongDao.insertAtTailKeepingOrder(playlistLocalId, toAppend);
			}

			// 更新统计并广播
			int count = playlistSongDao.getCount(playlistLocalId);
			playlistDao.updateStats(playlistLocalId, count, head.getChanged(), false);
			sendPlaylistChangedBroadcast(playlistLocalId);
			return !toAppend.isEmpty() || !ordinalsToRemove.isEmpty();
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