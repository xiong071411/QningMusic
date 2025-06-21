package com.watch.limusic.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class Album {
    private String id;
    private String name;
    private String artist;
    private String artistId;
    private String coverArt;
    private int songCount;
    private int duration;
    private int playCount;
    private String created;
    private int year;
    private String played;
    private int userRating;
    private List<String> genres;
    private String musicBrainzId;
    private boolean isCompilation;
    private String sortName;
    private List<String> discTitles;
    private List<Artist> artists;
    private String displayArtist;

    public static class Artist {
        private String id;
        private String name;

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getArtist() {
        return artist;
    }

    public String getArtistId() {
        return artistId;
    }

    public String getCoverArt() {
        return coverArt;
    }

    public int getSongCount() {
        return songCount;
    }

    public int getDuration() {
        return duration;
    }

    public String getDisplayArtist() {
        return displayArtist;
    }

    public int getYear() {
        return year;
    }
} 