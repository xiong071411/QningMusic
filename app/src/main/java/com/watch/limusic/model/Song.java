package com.watch.limusic.model;

import android.util.Log;

public class Song {
    private static final String TAG = "Song";
    private static final int DEFAULT_DURATION = 180000; // 默认3分钟
    
    private String id;
    private String title;
    private String album;
    private String artist;
    private String genre;
    private int duration;
    private String coverArt;
    private String streamUrl;
    private String albumId;

    public Song(String id, String title, String artist, String album, String coverArt, String streamUrl, int duration) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.coverArt = coverArt;
        this.streamUrl = streamUrl;
        this.duration = duration;
        this.genre = "";
        this.albumId = "";
        
        // 记录异常时长
        if (duration <= 0) {
            Log.w(TAG, "歌曲 " + title + " 时长异常: " + duration);
        }
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }
    
    public String getGenre() {
        return genre;
    }
    
    public void setGenre(String genre) {
        this.genre = genre;
    }

    public String getCoverArtUrl() {
        return coverArt;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public int getDuration() {
        // 如果时长为0或负数，返回默认时长
        return duration > 0 ? duration : DEFAULT_DURATION;
    }

    public String getAlbumId() {
        return albumId;
    }

    public void setAlbumId(String albumId) {
        this.albumId = albumId;
    }
} 