package com.watch.limusic.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 专辑数据访问对象接口
 */
@Dao
public interface AlbumDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAlbum(AlbumEntity album);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAllAlbums(List<AlbumEntity> albums);
    
    @Update
    void updateAlbum(AlbumEntity album);
    
    @Query("SELECT * FROM albums ORDER BY name COLLATE NOCASE ASC")
    List<AlbumEntity> getAllAlbums();
    
    @Query("SELECT * FROM albums WHERE id = :albumId")
    AlbumEntity getAlbumById(String albumId);
    
    @Query("SELECT * FROM albums WHERE name LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE ASC")
    List<AlbumEntity> searchAlbums(String query);
    
    @Query("DELETE FROM albums WHERE id = :albumId")
    void deleteAlbum(String albumId);
    
    @Query("DELETE FROM albums")
    void deleteAllAlbums();
    
    @Query("SELECT COUNT(*) FROM albums")
    int getAlbumCount();
} 