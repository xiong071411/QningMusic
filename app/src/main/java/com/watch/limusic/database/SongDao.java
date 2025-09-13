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
    
    // 新增：分页模糊搜索（标题/艺术家/专辑），前缀优先，其次按标题不区分大小写
    @Query("SELECT * FROM songs \n" +
           "WHERE (title LIKE '%' || :query || '%') OR (artist LIKE '%' || :query || '%') OR (album LIKE '%' || :query || '%')\n" +
           "ORDER BY\n" +
           "  CASE\n" +
           "    WHEN title LIKE :query || '%' THEN 0\n" +
           "    WHEN artist LIKE :query || '%' THEN 1\n" +
           "    WHEN album LIKE :query || '%' THEN 2\n" +
           "    ELSE 3\n" +
           "  END,\n" +
           "  title COLLATE NOCASE\n" +
           "LIMIT :limit OFFSET :offset")
    List<SongEntity> searchSongsPaged(String query, int limit, int offset);
    
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

    // 新增：精确计算某首歌在全局排序中的索引（之前的行数）
    @Query("SELECT COUNT(*) FROM songs WHERE " +
           "(CASE WHEN initial = '#' THEN 0 WHEN initial BETWEEN '0' AND '9' THEN 1 ELSE 2 END) < :cat " +
           "OR ((CASE WHEN initial = '#' THEN 0 WHEN initial BETWEEN '0' AND '9' THEN 1 ELSE 2 END) = :cat AND (initial < :ini)) " +
           "OR ((CASE WHEN initial = '#' THEN 0 WHEN initial BETWEEN '0' AND '9' THEN 1 ELSE 2 END) = :cat AND (initial = :ini) AND (title COLLATE NOCASE < :title))")
    int getGlobalIndex(int cat, String ini, String title);

    // 新增：按艺术家名称聚合统计（去除首尾空白），保留原始展示名
    @Query("SELECT TRIM(artist) AS name, COUNT(*) AS songCount FROM songs GROUP BY TRIM(artist)")
    List<ArtistCount> getArtistCounts();

    // 新增：按艺术家精确（忽略大小写/空白）取歌，排序与列表一致
    @Query("SELECT * FROM songs WHERE LOWER(TRIM(artist)) = LOWER(TRIM(:artistName)) ORDER BY title COLLATE NOCASE")
    List<SongEntity> getSongsByArtist(String artistName);
} 