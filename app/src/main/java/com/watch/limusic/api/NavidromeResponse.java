package com.watch.limusic.api;

import com.google.gson.annotations.SerializedName;
import com.watch.limusic.model.Song;

import java.util.List;

public class NavidromeResponse {
    @SerializedName("subsonic-response")
    private SubsonicResponse response;

    public SubsonicResponse getResponse() {
        return response;
    }

    public static class SubsonicResponse {
        private String status;
        private String version;
        private Error error;
        private Directory directory;
        private List<Artist> artists;
        private List<Album> albums;
        private List<Song> songs;
        private SearchResult searchResult;

        public String getStatus() {
            return status;
        }

        public String getVersion() {
            return version;
        }

        public Error getError() {
            return error;
        }

        public Directory getDirectory() {
            return directory;
        }

        public List<Artist> getArtists() {
            return artists;
        }

        public List<Album> getAlbums() {
            return albums;
        }

        public List<Song> getSongs() {
            return songs;
        }

        public SearchResult getSearchResult() {
            return searchResult;
        }
    }

    public static class Error {
        private int code;
        private String message;

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class Directory {
        private String id;
        private String name;
        private List<Child> child;

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<Child> getChild() {
            return child;
        }
    }

    public static class Child {
        private String id;
        private String title;
        private String artist;
        private String album;
        private String coverArt;
        private int duration;
        private String contentType;
        private String path;

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

        public String getCoverArt() {
            return coverArt;
        }

        public int getDuration() {
            return duration;
        }

        public String getContentType() {
            return contentType;
        }

        public String getPath() {
            return path;
        }
    }

    public static class Artist {
        private String id;
        private String name;
        private int albumCount;

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public int getAlbumCount() {
            return albumCount;
        }
    }

    public static class Album {
        private String id;
        private String name;
        private String artist;
        private String coverArt;
        private int songCount;
        private int duration;
        private String year;

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getArtist() {
            return artist;
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

        public String getYear() {
            return year;
        }
    }

    public static class SearchResult {
        private List<Artist> artists;
        private List<Album> albums;
        private List<Song> songs;

        public List<Artist> getArtists() {
            return artists;
        }

        public List<Album> getAlbums() {
            return albums;
        }

        public List<Song> getSongs() {
            return songs;
        }
    }
} 