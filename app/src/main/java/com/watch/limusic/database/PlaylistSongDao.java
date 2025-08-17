package com.watch.limusic.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface PlaylistSongDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	void insertAll(List<PlaylistSongEntity> items);

	@Query("DELETE FROM playlist_songs WHERE playlistLocalId = :playlistLocalId")
	int deleteAllForPlaylist(long playlistLocalId);

	@Query("DELETE FROM playlist_songs WHERE playlistLocalId = :playlistLocalId AND ordinal IN (:ordinals)")
	int deleteByOrdinals(long playlistLocalId, List<Integer> ordinals);

	@Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistLocalId = :playlistLocalId")
	int getCount(long playlistLocalId);

	@Query("SELECT s.* FROM songs s JOIN playlist_songs ps ON s.id = ps.songId WHERE ps.playlistLocalId = :playlistLocalId ORDER BY ps.ordinal ASC LIMIT :limit OFFSET :offset")
	List<SongEntity> getSongsByPlaylist(long playlistLocalId, int limit, int offset);

	// 目标歌单中已存在的 songId（交集）
	@Query("SELECT songId FROM playlist_songs WHERE playlistLocalId = :playlistLocalId AND songId IN (:ids)")
	List<String> getExistingIds(long playlistLocalId, List<String> ids);

	// 为删除构建 songId->ordinal 映射
	@Query("SELECT ordinal FROM playlist_songs WHERE playlistLocalId = :playlistLocalId AND songId IN (:ids)")
	List<Integer> getOrdinalsForSongIds(long playlistLocalId, List<String> ids);

	@Query("UPDATE playlist_songs SET ordinal = ordinal + :delta WHERE playlistLocalId = :playlistLocalId")
	int shiftAllOrdinals(long playlistLocalId, int delta);

	@Query("UPDATE playlist_songs SET ordinal = ordinal + :delta WHERE playlistLocalId = :playlistLocalId AND ordinal >= :minOrdinal")
	int shiftAllOrdinalsFrom(long playlistLocalId, int minOrdinal, int delta);

	@Query("UPDATE playlist_songs SET ordinal = :newOrdinal WHERE playlistLocalId = :playlistLocalId AND ordinal = :oldOrdinal")
	int updateOrdinal(long playlistLocalId, int oldOrdinal, int newOrdinal);

	@Query("UPDATE playlist_songs SET ordinal = ordinal - 1 WHERE playlistLocalId = :playlistLocalId AND ordinal > :from AND ordinal <= :to")
	int collapseGap(long playlistLocalId, int from, int to);

	@Query("UPDATE playlist_songs SET ordinal = ordinal + 1 WHERE playlistLocalId = :playlistLocalId AND ordinal >= :to AND ordinal < :from")
	int expandGap(long playlistLocalId, int from, int to);

	// 新增：在闭区间内整体平移 ordinal，支持正负 delta
	@Query("UPDATE playlist_songs SET ordinal = ordinal + :delta WHERE playlistLocalId = :playlistLocalId AND ordinal BETWEEN :start AND :end")
	int shiftRange(long playlistLocalId, int start, int end, int delta);

	@Transaction
	default void insertAtHeadKeepingOrder(long playlistLocalId, List<String> songIdsInSelectOrder) {
		int n = songIdsInSelectOrder != null ? songIdsInSelectOrder.size() : 0;
		if (n <= 0) return;
		final int BIG = 1000000;
		shiftAllOrdinalsFrom(playlistLocalId, 0, BIG);
		long now = System.currentTimeMillis();
		java.util.ArrayList<PlaylistSongEntity> list = new java.util.ArrayList<>();
		for (int i = 0; i < n; i++) {
			list.add(new PlaylistSongEntity(playlistLocalId, songIdsInSelectOrder.get(i), i, now));
		}
		insertAll(list);
		shiftAllOrdinalsFrom(playlistLocalId, BIG, -(BIG - n));
	}

	// 新增：按尾部追加，保持“先添加的在前面”
	@Transaction
	default void insertAtTailKeepingOrder(long playlistLocalId, List<String> songIdsInSelectOrder) {
		int n = songIdsInSelectOrder != null ? songIdsInSelectOrder.size() : 0;
		if (n <= 0) return;
		int base = getCount(playlistLocalId);
		long now = System.currentTimeMillis();
		java.util.ArrayList<PlaylistSongEntity> list = new java.util.ArrayList<>();
		for (int i = 0; i < n; i++) {
			list.add(new PlaylistSongEntity(playlistLocalId, songIdsInSelectOrder.get(i), base + i, now));
		}
		insertAll(list);
	}

	@Transaction
	default void reorder(long playlistLocalId, int from, int to) {
		if (from == to) return;
		final int BIG = 1000000;
		if (from < to) {
			// 将 [from+1, to] 暂存到高位，释放 to 目标位
			shiftRange(playlistLocalId, from + 1, to, BIG);
			// 把移动项放到 to
			updateOrdinal(playlistLocalId, from, to);
			// 将暂存区整体回落到原区间并左移1（-BIG-1）
			shiftRange(playlistLocalId, from + 1 + BIG, to + BIG, -(BIG + 1));
		} else { // from > to
			// 将 [to, from-1] 暂存到高位，释放 to 目标位
			shiftRange(playlistLocalId, to, from - 1, BIG);
			// 把移动项放到 to
			updateOrdinal(playlistLocalId, from, to);
			// 将暂存区整体回落并右移1（-BIG+1）
			shiftRange(playlistLocalId, to + BIG, from - 1 + BIG, -(BIG - 1));
		}
	}
} 