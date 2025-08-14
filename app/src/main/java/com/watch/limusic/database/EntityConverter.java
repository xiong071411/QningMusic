package com.watch.limusic.database;

import com.watch.limusic.model.Album;
import com.watch.limusic.model.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体转换工具类，用于Model和Entity之间的转换
 */
public class EntityConverter {

    /**
     * 将Album模型转换为AlbumEntity
     */
    public static AlbumEntity toAlbumEntity(Album album) {
        return new AlbumEntity(
                album.getId(),
                album.getName(),
                album.getArtist(),
                album.getArtistId(),
                album.getCoverArt(),
                album.getSongCount(),
                album.getDuration(),
                album.getYear()
        );
    }

    /**
     * 将AlbumEntity转换为Album模型
     */
    public static Album toAlbum(AlbumEntity entity) {
        // 由于Album类没有合适的构造函数，这里使用反射来创建实例
        // 实际项目中应该修改Album类添加合适的构造函数
        Album album = new Album();
        
        // 使用反射设置字段值
        try {
            java.lang.reflect.Field idField = Album.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(album, entity.getId());
            
            java.lang.reflect.Field nameField = Album.class.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(album, entity.getName());
            
            java.lang.reflect.Field artistField = Album.class.getDeclaredField("artist");
            artistField.setAccessible(true);
            artistField.set(album, entity.getArtist());
            
            java.lang.reflect.Field artistIdField = Album.class.getDeclaredField("artistId");
            artistIdField.setAccessible(true);
            artistIdField.set(album, entity.getArtistId());
            
            java.lang.reflect.Field coverArtField = Album.class.getDeclaredField("coverArt");
            coverArtField.setAccessible(true);
            coverArtField.set(album, entity.getCoverArt());
            
            java.lang.reflect.Field songCountField = Album.class.getDeclaredField("songCount");
            songCountField.setAccessible(true);
            songCountField.set(album, entity.getSongCount());
            
            java.lang.reflect.Field durationField = Album.class.getDeclaredField("duration");
            durationField.setAccessible(true);
            durationField.set(album, entity.getDuration());
            
            java.lang.reflect.Field yearField = Album.class.getDeclaredField("year");
            yearField.setAccessible(true);
            yearField.set(album, entity.getYear());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return album;
    }

    /**
     * 将多个Album模型转换为AlbumEntity列表
     */
    public static List<AlbumEntity> toAlbumEntities(List<Album> albums) {
        List<AlbumEntity> entities = new ArrayList<>();
        for (Album album : albums) {
            entities.add(toAlbumEntity(album));
        }
        return entities;
    }

    /**
     * 将多个AlbumEntity转换为Album列表
     */
    public static List<Album> toAlbums(List<AlbumEntity> entities) {
        List<Album> albums = new ArrayList<>();
        for (AlbumEntity entity : entities) {
            albums.add(toAlbum(entity));
        }
        return albums;
    }

    /**
     * 将Song模型转换为SongEntity
     */
    public static SongEntity toSongEntity(Song song) {
        // 创建一个基本的SongEntity
        SongEntity entity = new SongEntity(
                song.getId(),
                song.getTitle(),
                song.getArtist(),
                song.getAlbum(),
                song.getCoverArtUrl(),
                song.getStreamUrl(),
                song.getDuration(),
                song.getAlbumId()
        );
        
        // 设置缓存状态
        entity.setCached(false);
        
        return entity;
    }

    /**
     * 将SongEntity转换为Song模型
     */
    public static Song toSong(SongEntity entity) {
        Song song = new Song(
                entity.getId(),
                entity.getTitle(),
                entity.getArtist(),
                entity.getAlbum(),
                entity.getCoverArt(),
                entity.getStreamUrl(),
                entity.getDuration()
        );
        song.setAlbumId(entity.getAlbumId());
        if (entity.getGenre() != null) {
            song.setGenre(entity.getGenre());
        }
        return song;
    }

    /**
     * 将多个Song模型转换为SongEntity列表
     */
    public static List<SongEntity> toSongEntities(List<Song> songs) {
        List<SongEntity> entities = new ArrayList<>();
        for (Song song : songs) {
            entities.add(toSongEntity(song));
        }
        return entities;
    }

    /**
     * 将多个SongEntity转换为Song列表
     */
    public static List<Song> toSongs(List<SongEntity> entities) {
        List<Song> songs = new ArrayList<>();
        for (SongEntity entity : entities) {
            songs.add(toSong(entity));
        }
        return songs;
    }
} 