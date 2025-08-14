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
} 