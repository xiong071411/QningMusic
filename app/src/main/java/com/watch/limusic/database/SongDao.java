package com.watch.limusic.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 歌曲数据访问对象接口
 */
@Dao
public interface SongDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSong(SongEntity song);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAllSongs(List<SongEntity> songs);
    
    @Update
    void updateSong(SongEntity song);
    
    @Query("SELECT * FROM songs ORDER BY title")
    List<SongEntity> getAllSongs();
    
    @Query("SELECT * FROM songs WHERE albumId = :albumId")
    List<SongEntity> getSongsByAlbumId(String albumId);
    
    @Query("SELECT * FROM songs WHERE id = :songId")
    SongEntity getSongById(String songId);
    
    // 新增：根据ID列表取歌名，提示重复时使用
    @Query("SELECT title FROM songs WHERE id IN (:ids)")
    List<String> getTitlesByIds(List<String> ids);
    
    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' ORDER BY title")
    List<SongEntity> searchSongs(String query);
    
    @Query("UPDATE songs SET isCached = :isCached WHERE id = :songId")
    void updateCacheStatus(String songId, boolean isCached);
    
    @Query("UPDATE songs SET streamUrl = :streamUrl WHERE id = :songId")
    void updateStreamUrl(String songId, String streamUrl);

    @Query("SELECT * FROM songs WHERE isCached = 1 ORDER BY title")
    List<SongEntity> getCachedSongs();
    
    @Query("SELECT * FROM songs WHERE isCached = 1 AND albumId = :albumId")
    List<SongEntity> getCachedSongsByAlbumId(String albumId);
    
    @Query("DELETE FROM songs WHERE id = :songId")
    void deleteSong(String songId);
    
    @Query("DELETE FROM songs WHERE albumId = :albumId")
    void deleteSongsByAlbumId(String albumId);
    
    @Query("DELETE FROM songs")
    void deleteAllSongs();
    
    @Query("SELECT COUNT(*) FROM songs WHERE isCached = 1")
    int getCachedSongCount();
    
    @Query("SELECT COUNT(*) FROM songs")
    int getSongCount();

    // 轻量方案新增：范围查询（维持与 UI 一致的排序规则）
    @Query("SELECT * FROM songs\n" +
           "ORDER BY\n" +
           "  CASE\n" +
           "    WHEN initial = '#' THEN 0\n" +
           "    WHEN initial BETWEEN '0' AND '9' THEN 1\n" +
           "    ELSE 2\n" +
           "  END,\n" +
           "  initial,\n" +
           "  title COLLATE NOCASE\n" +
           "LIMIT :limit OFFSET :offset")
    List<SongEntity> getSongsRange(int limit, int offset);

    // 轻量方案新增：按 initial 分组计数（用于计算字母锚点的全局偏移）
    @Query("SELECT initial, COUNT(*) AS cnt FROM songs GROUP BY initial")
    List<InitialCount> getCountsByInitial();

    // 轻量方案新增：统计总数（与 getSongCount 相同，保留以语义清晰）
    @Query("SELECT COUNT(*) FROM songs")
    int getTotalSongCount();
} 