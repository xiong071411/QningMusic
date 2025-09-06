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

	// 新增：手动同步回调
	public interface SyncCallback {
		void onDone(boolean ok, String message);
	}

	// 新增：手动绑定并同步指定歌单
	public void manualBindAndSync(long playlistLocalId, SyncCallback callback) {
		new Thread(() -> {
			try {
				PlaylistEntity p = playlistDao.getByLocalId(playlistLocalId);
				if (p == null) { if (callback != null) callback.onDone(false, "歌单不存在"); return; }
				boolean bound = p.getServerId() != null && !p.getServerId().isEmpty();
				String sid = p.getServerId();
				// 若未绑定，先尝试按名称+owner 匹配，然后必要时创建远端再绑定
				if (!bound) {
					try {
						NavidromeApi.PlaylistsEnvelope env = api.getPlaylists();
						NavidromeApi.PlaylistsResponse r = env != null ? env.getResponse() : null;
						List<NavidromeApi.Playlist> remote = r != null && r.getPlaylists() != null ? r.getPlaylists().getList() : new ArrayList<>();
						String owner = api.getCurrentUsername();
						NavidromeApi.Playlist matched = null;
						long newest = -1L;
						for (NavidromeApi.Playlist rp : remote) {
							if (rp.getName() != null && rp.getName().equals(p.getName())) {
								if (owner == null || owner.isEmpty() || owner.equals(rp.getOwner())) {
									if (rp.getChanged() >= newest) { newest = rp.getChanged(); matched = rp; }
								}
							}
						}
						if (matched == null) {
							// 尝试创建远端歌单
							boolean ok = api.createPlaylist(p.getName(), p.isPublic());
							if (ok) {
								NavidromeApi.PlaylistsEnvelope env2 = api.getPlaylists();
								NavidromeApi.PlaylistsResponse r2 = env2 != null ? env2.getResponse() : null;
								List<NavidromeApi.Playlist> remote2 = r2 != null && r2.getPlaylists() != null ? r2.getPlaylists().getList() : new ArrayList<>();
								for (NavidromeApi.Playlist rp : remote2) {
									if (rp.getName() != null && rp.getName().equals(p.getName())) {
										if (owner == null || owner.isEmpty() || owner.equals(rp.getOwner())) {
											matched = rp; break;
										}
									}
								}
							} else {
								if (callback != null) callback.onDone(false, "服务器创建失败");
								return;
							}
						}
						if (matched != null) {
							sid = matched.getId();
							playlistDao.bindServerId(p.getLocalId(), sid);
							p.setServerId(sid);
							p.setSongCount(matched.getSongCount());
							p.setChangedAt(matched.getChanged());
							p.setOwner(matched.getOwner());
							p.setSyncDirty(false);
							playlistDao.update(p);
							bound = true;
						}
					} catch (Exception e) {
						if (callback != null) callback.onDone(false, "网络错误，无法绑定远端");
						return;
					}
				}
				if (!bound) { if (callback != null) callback.onDone(false, "未绑定远端，稍后重试"); return; }
				// 差集推送
				try {
					List<String> localIds = playlistSongDao.getSongIdsOrdered(p.getLocalId());
					NavidromeApi.PlaylistEnvelope detail = api.getPlaylist(sid);
					NavidromeApi.RemotePlaylist remotePl = detail != null && detail.getResponse() != null ? detail.getResponse().getPlaylist() : null;
					java.util.HashSet<String> remoteSet = new java.util.HashSet<>();
					if (remotePl != null && remotePl.getEntries() != null) {
						for (com.watch.limusic.model.Song s : remotePl.getEntries()) remoteSet.add(s.getId());
					}
					java.util.ArrayList<String> diff = new java.util.ArrayList<>();
					if (localIds != null) {
						for (String id : localIds) if (!remoteSet.contains(id)) diff.add(id);
					}
					if (!diff.isEmpty()) {
						boolean ok = api.updatePlaylist(sid, null, null, diff, null);
						if (!ok) { if (callback != null) callback.onDone(false, "服务器拒绝同步"); return; }
						playlistDao.updateStats(p.getLocalId(), playlistSongDao.getCount(p.getLocalId()), System.currentTimeMillis(), false);
					}
					sendPlaylistsUpdatedBroadcast();
					if (callback != null) callback.onDone(true, diff.isEmpty() ? "已与服务器一致" : "同步成功");
					return;
				} catch (Exception e) {
					if (callback != null) callback.onDone(false, "同步失败: " + e.getMessage());
					return;
				}
			} catch (Exception e) {
				if (callback != null) callback.onDone(false, "同步异常: " + e.getMessage());
			}
		}).start();
	}

	// 新增：创建歌单并立即添加歌曲（服务器优先，失败则本地暂存，回调给UI）
	public void createPlaylistAndAddSongs(String name, boolean isPublic, List<String> orderedSongIds, AddCallback callback) {
		if (name == null || name.trim().isEmpty()) {
			if (callback != null) callback.onResult(new ArrayList<>(), false);
			return;
		}
		final String finalName = name.trim();
		new Thread(() -> {
			PlaylistEntity local = null;
			boolean serverCreated = false;
			String boundServerId = null;
			try {
				// 1) 先请求服务端创建，拿到最新服务端列表做绑定（Navidrome 不返回 ID，只能通过 getPlaylists 匹配）
				try {
					serverCreated = api.createPlaylist(finalName, isPublic);
				} catch (IOException ioe) {
					serverCreated = false;
				}
				if (serverCreated) {
					// 拉取列表，并尝试找到属于当前用户、名称匹配，且 changed 较新的记录
					NavidromeApi.PlaylistsEnvelope env = null;
					try { env = api.getPlaylists(); } catch (IOException ignore) {}
					List<NavidromeApi.Playlist> remote = env != null && env.getResponse() != null && env.getResponse().getPlaylists() != null
						? env.getResponse().getPlaylists().getList() : new ArrayList<>();
					String owner = api.getCurrentUsername();
					long newestChanged = -1L;
					for (NavidromeApi.Playlist rp : remote) {
						if (rp == null) continue;
						if (rp.getName() != null && rp.getName().equals(finalName)) {
							// 若服务器返回 owner，用 owner 过滤；否则仅按名称匹配
							if (owner == null || owner.isEmpty() || owner.equals(rp.getOwner())) {
								long ch = rp.getChanged();
								if (ch >= newestChanged) {
									newestChanged = ch;
									boundServerId = rp.getId();
								}
							}
						}
					}
					// 立即在本地头部对账，创建或更新带 serverId 的头
					try { syncPlaylistsHeader(); } catch (Exception ignore) {}
				}

				// 2) 若没匹配到 serverId，则先创建本地占位
				if (boundServerId == null || boundServerId.isEmpty()) {
					local = new PlaylistEntity(finalName, isPublic);
					local.setDeleted(false);
					local.setSyncDirty(true);
					long lid = playlistDao.insert(local);
					local.setLocalId(lid);
				} else {
					// 确保存在对应本地记录
					long lid = ensureLocalFromRemoteHeader(boundServerId, finalName, isPublic, 0, System.currentTimeMillis());
					local = playlistDao.getByLocalId(lid);
				}

				// 3) 添加歌曲：本地先写，再尝试服务器同步（若已绑定）
				List<String> ordered = orderedSongIds != null ? new ArrayList<>(orderedSongIds) : new ArrayList<>();
				List<String> existing = playlistSongDao.getExistingIds(local.getLocalId(), ordered);
				List<String> toAdd = new ArrayList<>();
				for (String id : ordered) if (!existing.contains(id)) toAdd.add(id);
				List<String> skippedTitles = existing != null && !existing.isEmpty() ? db.songDao().getTitlesByIds(existing) : new ArrayList<>();
				if (!toAdd.isEmpty()) {
					playlistSongDao.insertAtTailKeepingOrder(local.getLocalId(), toAdd);
				}
				int count = playlistSongDao.getCount(local.getLocalId());
				playlistDao.updateStats(local.getLocalId(), count, System.currentTimeMillis(), true);
				sendPlaylistChangedBroadcast(local.getLocalId());

				boolean serverOk = false;
				try {
					PlaylistEntity p = playlistDao.getByLocalId(local.getLocalId());
					if (p != null && p.getServerId() != null && !p.getServerId().isEmpty()) {
						serverOk = api.updatePlaylist(p.getServerId(), null, null, toAdd, null);
						if (serverOk) playlistDao.updateStats(local.getLocalId(), count, System.currentTimeMillis(), false);
					}
				} catch (Exception e) { Log.w(TAG, "创建并添加歌曲：服务器同步失败: " + e.getMessage()); }

				if (callback != null) callback.onResult(skippedTitles, serverOk);
			} catch (Exception e) {
				Log.w(TAG, "创建歌单并添加歌曲失败: " + e.getMessage());
				if (callback != null) callback.onResult(new ArrayList<>(), false);
			}
		}).start();
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
		// 对于本地新建但尚未同步（serverId 为空）的记录，不在这里删除（保留以待绑定）
		List<PlaylistEntity> locals = playlistDao.getAllIncludingDeleted();
		for (PlaylistEntity pl : locals) {
			String sid = pl.getServerId();
			if (sid == null || sid.isEmpty()) continue; // 本地新建待同步，保留
			if (!serverIds.contains(sid)) {
				playlistDao.softDelete(pl.getLocalId(), System.currentTimeMillis());
			}
		}
		// 对齐/新增现存项：优先尝试与同名本地-only 歌单合并（绑定 serverId），避免重复
		for (NavidromeApi.Playlist rp : remote) {
			PlaylistEntity exist = playlistDao.getByServerId(rp.getId());
			if (exist == null) {
				// 尝试寻找同名的本地-only 歌单进行绑定
				PlaylistEntity localOnly = playlistDao.findLocalOnlyByName(rp.getName());
				if (localOnly != null) {
					playlistDao.bindServerId(localOnly.getLocalId(), rp.getId());
					localOnly.setServerId(rp.getId());
					localOnly.setSongCount(rp.getSongCount());
					localOnly.setChangedAt(rp.getChanged());
					localOnly.setOwner(rp.getOwner());
					localOnly.setSyncDirty(false);
					playlistDao.update(localOnly);
				} else {
				String name = ensureUniqueName(rp.getName());
				PlaylistEntity add = new PlaylistEntity(name, rp.isPublic());
				add.setServerId(rp.getId());
				add.setSongCount(rp.getSongCount());
				add.setChangedAt(rp.getChanged());
				add.setOwner(rp.getOwner());
				add.setSyncDirty(false);
				long id = playlistDao.insert(add);
				add.setLocalId(id);
				}
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

	// 新增：自动绑定与同步本地未绑定的歌单（在列表加载时调用）
	public void autoBindAndSyncPending() {
		new Thread(() -> {
			try {
				NavidromeApi.PlaylistsEnvelope env = api.getPlaylists();
				NavidromeApi.PlaylistsResponse r = env != null ? env.getResponse() : null;
				List<NavidromeApi.Playlist> remote = r != null && r.getPlaylists() != null ? r.getPlaylists().getList() : new ArrayList<>();
				if (remote == null) remote = new ArrayList<>();
				List<PlaylistEntity> localsOnly = playlistDao.getLocalOnlyActive();
				String owner = api.getCurrentUsername();
				if (localsOnly != null) {
					for (PlaylistEntity local : localsOnly) {
						NavidromeApi.Playlist matched = null;
						long newest = -1L;
						for (NavidromeApi.Playlist rp : remote) {
							if (rp.getName() != null && rp.getName().equals(local.getName())) {
								if (owner == null || owner.isEmpty() || owner.equals(rp.getOwner())) {
									if (rp.getChanged() >= newest) { newest = rp.getChanged(); matched = rp; }
								}
							}
						}
						if (matched != null) {
							playlistDao.bindServerId(local.getLocalId(), matched.getId());
							local.setServerId(matched.getId());
							local.setSongCount(matched.getSongCount());
							local.setChangedAt(matched.getChanged());
							local.setOwner(matched.getOwner());
							local.setSyncDirty(false);
							playlistDao.update(local);
							// 推送差集
							try {
								List<String> localIds = playlistSongDao.getSongIdsOrdered(local.getLocalId());
								NavidromeApi.PlaylistEnvelope detail = api.getPlaylist(matched.getId());
								NavidromeApi.RemotePlaylist remotePl = detail != null && detail.getResponse() != null ? detail.getResponse().getPlaylist() : null;
								java.util.HashSet<String> remoteSet = new java.util.HashSet<>();
								if (remotePl != null && remotePl.getEntries() != null) {
									for (com.watch.limusic.model.Song s : remotePl.getEntries()) remoteSet.add(s.getId());
								}
								java.util.ArrayList<String> diff = new java.util.ArrayList<>();
								if (localIds != null) {
									for (String id : localIds) if (!remoteSet.contains(id)) diff.add(id);
								}
								if (!diff.isEmpty()) {
									api.updatePlaylist(matched.getId(), null, null, diff, null);
								}
							} catch (Exception ignore) {}
						}
					}
				}
				// 对已绑定但标记为脏数据(syncDirty=1)的歌单也进行差集补推
				List<PlaylistEntity> boundDirty = playlistDao.getBoundDirtyActive();
				if (boundDirty != null) {
					for (PlaylistEntity p : boundDirty) {
						try {
							List<String> localIds = playlistSongDao.getSongIdsOrdered(p.getLocalId());
							NavidromeApi.PlaylistEnvelope detail = api.getPlaylist(p.getServerId());
							NavidromeApi.RemotePlaylist remotePl = detail != null && detail.getResponse() != null ? detail.getResponse().getPlaylist() : null;
							java.util.HashSet<String> remoteSet = new java.util.HashSet<>();
							if (remotePl != null && remotePl.getEntries() != null) {
								for (com.watch.limusic.model.Song s : remotePl.getEntries()) remoteSet.add(s.getId());
							}
							java.util.ArrayList<String> diff = new java.util.ArrayList<>();
							if (localIds != null) {
								for (String id : localIds) if (!remoteSet.contains(id)) diff.add(id);
							}
							if (!diff.isEmpty()) {
								boolean ok = api.updatePlaylist(p.getServerId(), null, null, diff, null);
								if (ok) playlistDao.updateStats(p.getLocalId(), playlistSongDao.getCount(p.getLocalId()), System.currentTimeMillis(), false);
							}
						} catch (Exception ignore) {}
					}
				}
			} catch (Exception e) {
				Log.w(TAG, "autoBindAndSyncPending 失败: " + e.getMessage());
			}
		}).start();
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
			boolean ok = false; // 默认 false，只有确实调用了服务器并成功才置 true
			try {
				PlaylistEntity p = playlistDao.getByLocalId(playlistLocalId);
				if (p != null && p.getServerId() != null && !p.getServerId().isEmpty()) {
					ok = api.updatePlaylist(p.getServerId(), null, null, toAddFinal, null);
					if (ok) playlistDao.updateStats(playlistLocalId, countFinal, System.currentTimeMillis(), false);
				} else {
					ok = false;
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
					// 同名本地无serverId重复项一并软删除，避免多选弹窗里残留
					try { playlistDao.softDeleteLocalDuplicatesByName(p.getName(), System.currentTimeMillis()); } catch (Exception ignore) {}
					// 无论成功失败，都再触发一次对账以避免脏显
					try { syncPlaylistsHeader(); } catch (Exception ignore) {}
					sendPlaylistsUpdatedBroadcast();
				} else if (p != null) {
					// 删除的是本地占位：清理同名其他占位，防止残留
					try { playlistDao.softDeleteLocalDuplicatesByName(p.getName(), System.currentTimeMillis()); } catch (Exception ignore) {}
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