package com.watch.limusic.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.annotations.SerializedName;
import com.watch.limusic.model.Song;
import com.watch.limusic.model.Album;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Random;
import java.util.ArrayList;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

public class NavidromeApi {
    private static final String TAG = "NavidromeApi";
    private static final String API_VERSION = "1.16.1";
    private static final String CLIENT_NAME = "LiMusic";
    private static NavidromeApi instance;

    private final Context context;
    private final OkHttpClient client;
    private final Gson gson;
    private String serverUrl;
    private String username;
    private String password;
    private int serverPort;

    public static NavidromeApi getInstance(Context context) {
        if (instance == null) {
            instance = new NavidromeApi(context);
        }
        return instance;
    }

    private NavidromeApi(Context context) {
        this.context = context.getApplicationContext();
        this.gson = new Gson();

        // 设置 OkHttpClient，添加日志拦截器
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();

        loadCredentials();
    }

    private void loadCredentials() {
        SharedPreferences prefs = context.getSharedPreferences("navidrome_settings", Context.MODE_PRIVATE);
        serverUrl = prefs.getString("server_url", "");
        username = prefs.getString("username", "");
        password = prefs.getString("password", "");
        serverPort = Integer.parseInt(prefs.getString("server_port", "4533"));
    }

    private String generateSalt() {
        Random random = new Random();
        return String.valueOf(random.nextInt(900000000) + 100000000);
    }

    private String generateToken(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String input = password + salt;
            byte[] bytes = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Error generating token", e);
            return null;
        }
    }

    private HttpUrl.Builder getBaseUrlBuilder() {
        return new HttpUrl.Builder()
                .scheme(serverUrl.startsWith("https") ? "https" : "http")
                .host(serverUrl.replace("http://", "").replace("https://", ""))
                .port(serverPort)
                .addPathSegment("rest");
    }

    private Request.Builder getRequestBuilder(String endpoint) {
        String salt = generateSalt();
        String token = generateToken(password, salt);

        HttpUrl url = getBaseUrlBuilder()
                .addPathSegment(endpoint)
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .build();

        return new Request.Builder().url(url);
    }

    public boolean ping() throws IOException {
        Request request = getRequestBuilder("ping").build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    public SubsonicResponse<List<Album>> getAlbumList(String type, int size, int offset) throws IOException {
        String salt = generateSalt();
        String token = generateToken(password, salt);

        HttpUrl url = getBaseUrlBuilder()
                .addPathSegment("getAlbumList2")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .addQueryParameter("type", type)
                .addQueryParameter("size", String.valueOf(size))
                .addQueryParameter("offset", String.valueOf(offset))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response " + response);
            }
            return gson.fromJson(response.body().string(), new TypeToken<SubsonicResponse<List<Album>>>(){}.getType());
        }
    }

    public String getCoverArtUrl(String coverArtId) {
        String salt = generateSalt();
        String token = generateToken(password, salt);

        return getBaseUrlBuilder()
                .addPathSegment("getCoverArt")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("id", coverArtId)
                .addQueryParameter("size", "150")  // 合适的封面大小
                .build()
                .toString();
    }

    public List<Song> getAlbumSongs(String albumId) throws IOException {
        String salt = generateSalt();
        String token = generateToken(password, salt);

        HttpUrl url = getBaseUrlBuilder()
                .addPathSegment("getAlbum")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .addQueryParameter("id", albumId)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response " + response);
            }
            
            // 解析响应
            AlbumResponse albumResponse = gson.fromJson(
                response.body().string(), 
                new TypeToken<AlbumResponse>(){}.getType()
            );
            
            if (albumResponse != null && 
                albumResponse.getResponse() != null && 
                albumResponse.getResponse().getAlbum() != null) {
                
                AlbumData album = albumResponse.getResponse().getAlbum();
                List<Song> songs = album.getSongs();
                
                // 为每首歌曲设置专辑ID和专辑封面
                for (Song song : songs) {
                    song.setAlbumId(album.getId());
                }
                
                return songs;
            }
            
            return new ArrayList<>();
        }
    }

    public String getStreamUrl(String songId) {
        String salt = generateSalt();
        String token = generateToken(password, salt);

        return getBaseUrlBuilder()
                .addPathSegment("stream")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("id", songId)
                .build()
                .toString();
    }

    public List<Song> getAllSongs() throws IOException {
        String salt = generateSalt();
        String token = generateToken(password, salt);

        HttpUrl url = getBaseUrlBuilder()
                .addPathSegment("getRandomSongs")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .addQueryParameter("size", "500")  // 获取较大数量的歌曲
                .build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response " + response);
            }
            
            // 解析响应
            SongsResponse songsResponse = gson.fromJson(
                response.body().string(), 
                new TypeToken<SongsResponse>(){}.getType()
            );
            
            if (songsResponse != null && 
                songsResponse.getResponse() != null && 
                songsResponse.getResponse().getSongs() != null) {
                
                List<Song> songs = songsResponse.getResponse().getSongs();
                
                // 确保每首歌曲都有专辑ID
                for (Song song : songs) {
                    if (song.getAlbumId() == null) {
                        // 如果没有专辑ID，使用coverArt作为专辑ID
                        song.setAlbumId(song.getCoverArtUrl());
                    }
                }
                
                return songs;
            }
            
            return new ArrayList<>();
        }
    }

    private static class AlbumResponse {
        @SerializedName("subsonic-response")
        private AlbumResponseData response;

        public AlbumResponseData getResponse() {
            return response;
        }
    }

    private static class AlbumResponseData {
        private String status;
        private String version;
        private AlbumData album;
        private Error error;

        public boolean isSuccess() {
            return "ok".equals(status);
        }

        public AlbumData getAlbum() {
            return album;
        }
    }

    private static class SongsResponse {
        @SerializedName("subsonic-response")
        private SongsResponseData response;

        public SongsResponseData getResponse() {
            return response;
        }
    }

    private static class SongsResponseData {
        private String status;
        private String version;
        @SerializedName("randomSongs")
        private SongsData songsData;
        private Error error;

        public boolean isSuccess() {
            return "ok".equals(status);
        }

        public List<Song> getSongs() {
            return songsData != null ? songsData.getSongs() : new ArrayList<>();
        }
    }

    private static class SongsData {
        @SerializedName("song")
        private List<Song> songs;

        public List<Song> getSongs() {
            return songs != null ? songs : new ArrayList<>();
        }
    }

    private static class AlbumData {
        private String id;
        private String name;
        private String artist;
        @SerializedName("song")
        private List<Song> songs;

        public String getId() {
            return id;
        }

        public List<Song> getSongs() {
            return songs != null ? songs : new ArrayList<>();
        }
    }
} 