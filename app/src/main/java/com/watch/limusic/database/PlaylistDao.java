package com.watch.limusic.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PlaylistDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	long insert(PlaylistEntity playlist);

	@Update
	int update(PlaylistEntity playlist);

	@Query("DELETE FROM playlists WHERE localId = :localId")
	int deleteByLocalId(long localId);

	@Query("SELECT * FROM playlists WHERE isDeleted = 0 ORDER BY changedAt DESC")
	List<PlaylistEntity> getAll();

	// 新增：包含已删除项的全量列表，用于与服务端对账
	@Query("SELECT * FROM playlists ORDER BY changedAt DESC")
	List<PlaylistEntity> getAllIncludingDeleted();

	@Query("SELECT * FROM playlists WHERE localId = :localId")
	PlaylistEntity getByLocalId(long localId);

	@Query("SELECT * FROM playlists WHERE serverId = :serverId")
	PlaylistEntity getByServerId(String serverId);

	@Query("SELECT COUNT(*) FROM playlists WHERE name = :name AND isDeleted = 0")
	int countByName(String name);

	@Query("UPDATE playlists SET songCount = :count, changedAt = :changedAt, syncDirty = :syncDirty WHERE localId = :localId")
	int updateStats(long localId, int count, long changedAt, boolean syncDirty);

	@Query("UPDATE playlists SET name = :newName, changedAt = :changedAt, syncDirty = 1 WHERE localId = :localId")
	int rename(long localId, String newName, long changedAt);

	@Query("UPDATE playlists SET isPublic = :isPublic, changedAt = :changedAt, syncDirty = 1 WHERE localId = :localId")
	int setPublic(long localId, boolean isPublic, long changedAt);

	@Query("UPDATE playlists SET isDeleted = 1, syncDirty = 1, changedAt = :changedAt WHERE localId = :localId")
	int softDelete(long localId, long changedAt);

	// 新增：清理本地孤儿歌单（无服务端ID、未删除、且没有任何歌曲关联）
	@Query("DELETE FROM playlists WHERE (serverId IS NULL OR serverId = '') AND isDeleted = 0 AND localId NOT IN (SELECT DISTINCT playlistLocalId FROM playlist_songs)")
	int deleteOrphanLocalPlaylists();
} 