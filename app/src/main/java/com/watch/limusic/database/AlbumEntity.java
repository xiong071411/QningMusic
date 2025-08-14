package com.watch.limusic.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 专辑数据库实体类，用于离线存储专辑信息
 */
@Entity(tableName = "albums")
public class AlbumEntity {
    @PrimaryKey
    @NonNull
    private String id;
    private String name;
    private String artist;
    private String artistId;
    private String coverArt;
    private int songCount;
    private int duration;
    private int year;
    private long lastUpdated; // 上次从服务器更新的时间戳

    public AlbumEntity(@NonNull String id, String name, String artist, String artistId,
                      String coverArt, int songCount, int duration, int year) {
        this.id = id;
        this.name = name;
        this.artist = artist;
        this.artistId = artistId;
        this.coverArt = coverArt;
        this.songCount = songCount;
        this.duration = duration;
        this.year = year;
        this.lastUpdated = System.currentTimeMillis();
    }

    @NonNull
    public String getId() {
        return id;
    }

    public void setId(@NonNull String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getArtistId() {
        return artistId;
    }

    public void setArtistId(String artistId) {
        this.artistId = artistId;
    }

    public String getCoverArt() {
        return coverArt;
    }

    public void setCoverArt(String coverArt) {
        this.coverArt = coverArt;
    }

    public int getSongCount() {
        return songCount;
    }

    public void setSongCount(int songCount) {
        this.songCount = songCount;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
} 