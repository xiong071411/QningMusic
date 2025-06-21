package com.watch.limusic.api;

import com.google.gson.annotations.SerializedName;
import com.watch.limusic.model.Album;

import java.util.List;

public class SubsonicResponse<T> {
    @SerializedName("subsonic-response")
    private ResponseData response;

    public static class ResponseData {
        private String status;
        private String version;
        private String type;
        private String serverVersion;
        private boolean openSubsonic;
        @SerializedName("albumList2")
        private AlbumList albumList;
        private Error error;

        public boolean isSuccess() {
            return "ok".equals(status);
        }

        public String getError() {
            return error != null ? error.message : null;
        }

        public List<Album> getAlbums() {
            return albumList != null ? albumList.album : null;
        }
    }

    public static class AlbumList {
        private List<Album> album;
    }

    public static class Error {
        private int code;
        private String message;
    }

    public boolean isSuccess() {
        return response != null && response.isSuccess();
    }

    public List<Album> getData() {
        return response != null ? response.getAlbums() : null;
    }

    public String getError() {
        return response != null ? response.getError() : "Unknown error";
    }
} 