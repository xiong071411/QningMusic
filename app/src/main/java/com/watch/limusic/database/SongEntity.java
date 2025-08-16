package com.watch.limusic.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 歌曲数据库实体类，用于离线存储歌曲信息
 */
@Entity(
    tableName = "songs",
    indices = {@Index("albumId"), @Index("initial"), @Index(value = {"initial", "title"})},
    foreignKeys = @ForeignKey(
        entity = AlbumEntity.class,
        parentColumns = "id",
        childColumns = "albumId",
        onDelete = ForeignKey.CASCADE
    )
)
public class SongEntity {
    @PrimaryKey
    @NonNull
    private String id;
    private String title;
    private String album;
    private String artist;
    private String genre;
    private int duration;
    private String coverArt;
    private String streamUrl;
    private String albumId;
    private boolean isCached; // 标记歌曲是否已缓存
    private long lastUpdated; // 上次从服务器更新的时间戳
    private long cacheTimestamp; // 歌曲缓存的时间戳
    private String initial; // 标题首字母（与 UI 排序/索引一致）

    public SongEntity(@NonNull String id, String title, String artist, String album,
                     String coverArt, String streamUrl, int duration, String albumId) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.coverArt = coverArt;
        this.streamUrl = streamUrl;
        this.duration = duration;
        this.albumId = albumId;
        this.genre = "";
        this.isCached = false;
        this.lastUpdated = System.currentTimeMillis();
        this.cacheTimestamp = 0;
        this.initial = "#"; // 默认占位，入库时由转换器或仓库填写
    }

    @NonNull
    public String getId() {
        return id;
    }

    public void setId(@NonNull String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public String getGenre() {
        return genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public String getCoverArt() {
        return coverArt;
    }

    public void setCoverArt(String coverArt) {
        this.coverArt = coverArt;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public void setStreamUrl(String streamUrl) {
        this.streamUrl = streamUrl;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public String getAlbumId() {
        return albumId;
    }

    public void setAlbumId(String albumId) {
        this.albumId = albumId;
    }

    public boolean isCached() {
        return isCached;
    }

    public void setCached(boolean cached) {
        isCached = cached;
        if (cached) {
            this.cacheTimestamp = System.currentTimeMillis();
        }
    }
    
    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public long getCacheTimestamp() {
        return cacheTimestamp;
    }
    
    public void setCacheTimestamp(long cacheTimestamp) {
        this.cacheTimestamp = cacheTimestamp;
    }

    public String getInitial() {
        return initial;
    }

    public void setInitial(String initial) {
        this.initial = initial;
    }
} 