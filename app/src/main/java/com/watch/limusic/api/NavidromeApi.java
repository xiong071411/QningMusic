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
import okhttp3.Interceptor;
import okhttp3.logging.HttpLoggingInterceptor;

public class NavidromeApi {
    private static final String TAG = "NavidromeApi";
    public static final String ACTION_NAVIDROME_CONFIG_UPDATED = "com.watch.limusic.NAVIDROME_CONFIG_UPDATED";
    private static final String API_VERSION = NavidromeClient.API_VERSION;
    private static final String CLIENT_NAME = NavidromeClient.CLIENT_NAME;
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

    // 新增：暴露当前用户名，供仓库在筛选远端歌单 owner 时使用
    public String getCurrentUsername() { return username; }

    private NavidromeApi(Context context) {
        this.context = context.getApplicationContext();
        this.gson = new Gson();

        // 设置 OkHttpClient，添加日志拦截器和User-Agent拦截器
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor(new UserAgentInterceptor())
                .build();

        loadCredentials();
    }

    // 将用户代理拦截器分离成独立的类，提高可维护性
    private static class UserAgentInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request originalRequest = chain.request();
            Request requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", CLIENT_NAME)
                    .build();
            return chain.proceed(requestWithUserAgent);
        }
    }

    private void loadCredentials() {
        SharedPreferences prefs = context.getSharedPreferences("navidrome_settings", Context.MODE_PRIVATE);
        serverUrl = prefs.getString("server_url", "");
        username = prefs.getString("username", "");
        password = prefs.getString("password", "");
        serverPort = Integer.parseInt(prefs.getString("server_port", "4533"));
    }

    // 显式刷新凭据，供外部在配置更新广播到达时调用
    public synchronized void reloadCredentials() { loadCredentials(); }

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
        // 使用内存中的最新配置（通过构造与广播刷新）
        String hostOnly = serverUrl == null ? "" : serverUrl.replace("http://", "").replace("https://", "");
        int slashIdx = hostOnly.indexOf('/');
        if (slashIdx >= 0) hostOnly = hostOnly.substring(0, slashIdx);
        int colonIdx = hostOnly.indexOf(':');
        if (colonIdx >= 0) hostOnly = hostOnly.substring(0, colonIdx);
        return new HttpUrl.Builder()
                .scheme(serverUrl != null && serverUrl.startsWith("https") ? "https" : "http")
                .host(hostOnly)
                .port(serverPort)
                .addPathSegment("rest");
    }

    // 统一构建请求（带签名参数）
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

    // 兼容旧版 Gson：parse json 字符串为 JsonObject
    private static com.google.gson.JsonObject parseJsonObjectCompat(String json) {
        try {
            com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
            return parser.parse(json).getAsJsonObject();
        } catch (Throwable t) {
            return null;
        }
    }

    // 使用传入配置进行一次性 ping 测试（不依赖单例缓存）
    public static boolean ping(String serverUrl, int serverPort, String username, String password) throws IOException {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient tempClient = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor(chain -> {
                    Request originalRequest = chain.request();
                    Request requestWithUserAgent = originalRequest.newBuilder()
                            .header("User-Agent", CLIENT_NAME)
                            .build();
                    return chain.proceed(requestWithUserAgent);
                })
                .build();

        String salt;
        {
            java.util.Random random = new java.util.Random();
            salt = String.valueOf(random.nextInt(900000000) + 100000000);
        }
        String token;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            String input = password + salt;
            byte[] bytes = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            token = sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("无法生成校验令牌", e);
        }

        String scheme = serverUrl != null && serverUrl.startsWith("https") ? "https" : "http";
        String hostPart = serverUrl == null ? "" : serverUrl.replace("http://", "").replace("https://", "");
        int slashIdx = hostPart.indexOf('/');
        if (slashIdx >= 0) hostPart = hostPart.substring(0, slashIdx);
        int colonIdx = hostPart.indexOf(':');
        if (colonIdx >= 0) hostPart = hostPart.substring(0, colonIdx);

        HttpUrl url = new HttpUrl.Builder()
                .scheme(scheme)
                .host(hostPart)
                .port(serverPort)
                .addPathSegment("rest")
                .addPathSegment("ping")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .build();

        Request request = new Request.Builder().url(url).build();
        try (Response response = tempClient.newCall(request).execute()) {
            return response.isSuccessful();
        }
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
            AlbumResponse albumResponse = gson.fromJson(
                response.body().string(), 
                new TypeToken<AlbumResponse>(){}.getType()
            );
            if (albumResponse != null && 
                albumResponse.getResponse() != null && 
                albumResponse.getResponse().getAlbum() != null) {
                AlbumData album = albumResponse.getResponse().getAlbum();
                List<Song> songs = album.getSongs();
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

    /**
     * 获取歌词（Subsonic: getLyrics）
     * 注意：Navidrome 兼容实现通常只支持按 artist+title 查询
     */
    public String getLyrics(String artist, String title) throws IOException {
        String salt = generateSalt();
        String token = generateToken(password, salt);
        HttpUrl.Builder b = getBaseUrlBuilder()
                .addPathSegment("getLyrics")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json");
        if (artist != null && !artist.isEmpty()) b.addQueryParameter("artist", artist);
        if (title != null && !title.isEmpty()) b.addQueryParameter("title", title);
        Request request = new Request.Builder().url(b.build()).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            String json = response.body().string();
            try {
                com.google.gson.JsonObject root = parseJsonObjectCompat(json);
                if (root == null) return null;
                com.google.gson.JsonObject sr = root.has("subsonic-response") && root.get("subsonic-response").isJsonObject()
                        ? root.getAsJsonObject("subsonic-response") : null;
                if (sr == null) return null;
                if (sr.has("lyrics")) {
                    com.google.gson.JsonElement le = sr.get("lyrics");
                    if (le.isJsonPrimitive()) {
                        String val = le.getAsString();
                        return (val != null && !val.trim().isEmpty()) ? val : null;
                    } else if (le.isJsonObject()) {
                        com.google.gson.JsonObject lo = le.getAsJsonObject();
                        // 尽量宽松：优先 value，其次 lyrics；否则取第一个字符串字段
                        if (lo.has("value") && lo.get("value").isJsonPrimitive()) {
                            String val = lo.get("value").getAsString();
                            if (val != null && !val.trim().isEmpty()) return val;
                        }
                        if (lo.has("lyrics") && lo.get("lyrics").isJsonPrimitive()) {
                            String val = lo.get("lyrics").getAsString();
                            if (val != null && !val.trim().isEmpty()) return val;
                        }
                        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : lo.entrySet()) {
                            if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                                String val = e.getValue().getAsString();
                                if (val != null && !val.trim().isEmpty()) return val;
                            }
                        }
                    }
                }
                // 宽松回退：尝试直接取 sr 下的任意字符串字段
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : sr.entrySet()) {
                    if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                        String val = e.getValue().getAsString();
                        if (val != null && !val.trim().isEmpty()) return val;
                    }
                }
            } catch (Exception ignore) {}
            return null;
        }
    }

    public String getTranscodedStreamUrl(String songId, String format, int maxBitRateKbps) {
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
                .addQueryParameter("format", format)
                .addQueryParameter("maxBitRate", String.valueOf(maxBitRateKbps))
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
                .addQueryParameter("size", "500")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response " + response);
            }
            SongsResponse songsResponse = gson.fromJson(
                response.body().string(), 
                new TypeToken<SongsResponse>(){}.getType()
            );
            if (songsResponse != null && 
                songsResponse.getResponse() != null && 
                songsResponse.getResponse().getSongs() != null) {
                List<Song> songs = songsResponse.getResponse().getSongs();
                return songs;
            }
            return new ArrayList<>();
        }
    }

    // ============ Playlists (Navidrome/Subsonic) ============
    public PlaylistsEnvelope getPlaylists() throws IOException {
        String salt = generateSalt();
        String token = generateToken(password, salt);
        HttpUrl url = getBaseUrlBuilder()
                .addPathSegment("getPlaylists")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .build();
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected response " + response);
            String json = response.body().string();
            // 兼容：当 playlists.playlist 为单对象而非数组时，包装成数组
            try {
                com.google.gson.JsonObject root = parseJsonObjectCompat(json);
                if (root != null) {
                    com.google.gson.JsonObject sr = root.has("subsonic-response") && root.get("subsonic-response").isJsonObject()
                            ? root.getAsJsonObject("subsonic-response") : null;
                    if (sr != null && sr.has("playlists") && sr.get("playlists").isJsonObject()) {
                        com.google.gson.JsonObject pls = sr.getAsJsonObject("playlists");
                        if (pls.has("playlist")) {
                            com.google.gson.JsonElement pl = pls.get("playlist");
                            if (pl != null && pl.isJsonObject()) {
                                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                                arr.add(pl.getAsJsonObject());
                                pls.add("playlist", arr);
                                json = root.toString();
                            }
                        }
                    }
                }
            } catch (Exception ignore) {}
            PlaylistsEnvelope env = gson.fromJson(json, new TypeToken<PlaylistsEnvelope>(){}.getType());
            return env;
        }
    }

    public PlaylistEnvelope getPlaylist(String playlistId) throws IOException {
        String salt = generateSalt();
        String token = generateToken(password, salt);
        HttpUrl url = getBaseUrlBuilder()
                .addPathSegment("getPlaylist")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .addQueryParameter("id", playlistId)
                .build();
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected response " + response);
            String json = response.body().string();
            // 兼容：当 playlist.entry 为单对象而非数组时，包装成数组
            try {
                com.google.gson.JsonObject root = parseJsonObjectCompat(json);
                if (root != null) {
                    com.google.gson.JsonObject sr = root.has("subsonic-response") && root.get("subsonic-response").isJsonObject()
                            ? root.getAsJsonObject("subsonic-response") : null;
                    if (sr != null && sr.has("playlist") && sr.get("playlist").isJsonObject()) {
                        com.google.gson.JsonObject pl = sr.getAsJsonObject("playlist");
                        if (pl.has("entry")) {
                            com.google.gson.JsonElement en = pl.get("entry");
                            if (en != null && en.isJsonObject()) {
                                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                                arr.add(en.getAsJsonObject());
                                pl.add("entry", arr);
                                json = root.toString();
                            }
                        }
                    }
                }
            } catch (Exception ignore) {}
            PlaylistEnvelope env = gson.fromJson(json, new TypeToken<PlaylistEnvelope>(){}.getType());
            return env;
        }
    }

    public boolean createPlaylist(String name, boolean isPublic) throws IOException {
        String salt = generateSalt();
        String token = generateToken(password, salt);
        HttpUrl url = getBaseUrlBuilder()
                .addPathSegment("createPlaylist")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .addQueryParameter("name", name)
                .addQueryParameter("public", String.valueOf(isPublic))
                .build();
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    public boolean updatePlaylist(String playlistId, String newName, Boolean isPublic,
                                  List<String> songIdsToAdd, List<Integer> indexesToRemove) throws IOException {
        String salt = generateSalt();
        String token = generateToken(password, salt);
        HttpUrl.Builder b = getBaseUrlBuilder()
                .addPathSegment("updatePlaylist")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .addQueryParameter("playlistId", playlistId);
        if (newName != null && !newName.isEmpty()) b.addQueryParameter("name", newName);
        if (isPublic != null) b.addQueryParameter("public", String.valueOf(isPublic));
        if (songIdsToAdd != null) {
            for (String id : songIdsToAdd) {
                b.addQueryParameter("songIdToAdd", id);
            }
        }
        if (indexesToRemove != null) {
            for (Integer idx : indexesToRemove) {
                b.addQueryParameter("songIndexToRemove", String.valueOf(idx));
            }
        }
        Request request = new Request.Builder().url(b.build()).build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    public boolean deletePlaylist(String playlistId) throws IOException {
        String salt = generateSalt();
        String token = generateToken(password, salt);
        HttpUrl url = getBaseUrlBuilder()
                .addPathSegment("deletePlaylist")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .addQueryParameter("id", playlistId)
                .build();
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    // ------- inner DTOs for playlists -------
    public static class PlaylistsEnvelope {
        @SerializedName("subsonic-response")
        private PlaylistsResponse response;
        public PlaylistsResponse getResponse() { return response; }
    }
    public static class PlaylistsResponse {
        private String status;
        private String version;
        private Playlists playlists;
        public boolean isSuccess() { return "ok".equals(status); }
        public Playlists getPlaylists() { return playlists; }
    }
    public static class Playlists {
        @SerializedName("playlist")
        private List<Playlist> list;
        public List<Playlist> getList() { return list != null ? list : new ArrayList<>(); }
    }
    public static class PlaylistEnvelope {
        @SerializedName("subsonic-response")
        private PlaylistResponse response;
        public PlaylistResponse getResponse() { return response; }
    }
    public static class PlaylistResponse {
        private String status;
        private String version;
        private RemotePlaylist playlist;
        public boolean isSuccess() { return "ok".equals(status); }
        public RemotePlaylist getPlaylist() { return playlist; }
    }
    public static class Playlist {
        private String id;
        private String name;
        private String comment;
        private String owner;
        @SerializedName("public")
        private boolean isPublic;
        private String coverArt;
        private int songCount;
        private long duration;
        // 注意：Navidrome 这里是 ISO8601 字符串，如 2025-08-17T03:24:23.660116375Z
        private String created;
        private String changed;
        public String getId() { return id; }
        public String getName() { return name; }
        public String getOwner() { return owner; }
        public boolean isPublic() { return isPublic; }
        public int getSongCount() { return songCount; }
        public String getCoverArt() { return coverArt; }
        public long getCreated() { return parseIsoToEpoch(created); }
        public long getChanged() { return parseIsoToEpoch(changed); }
        private long parseIsoToEpoch(String value) {
            try {
                if (value == null || value.isEmpty()) return 0L;
                // 规范化到毫秒精度：YYYY-MM-DDTHH:mm:ss.SSSZ
                int dot = value.indexOf('.');
                int z = value.indexOf('Z', dot >= 0 ? dot : 0);
                if (dot >= 0 && z > dot) {
                    String frac = value.substring(dot + 1, z);
                    String ms;
                    if (frac.length() >= 3) ms = frac.substring(0, 3);
                    else if (frac.length() == 2) ms = frac + "0";
                    else if (frac.length() == 1) ms = frac + "00";
                    else ms = "000";
                    value = value.substring(0, dot) + "." + ms + "Z";
                }
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return sdf.parse(value).getTime();
            } catch (Exception ignore) {
                return 0L;
            }
        }
    }
    public static class RemotePlaylist {
        private String id;
        private String name;
        private String owner;
        @SerializedName("public")
        private boolean isPublic;
        // ISO8601 字符串
        private String changed;
        @SerializedName("entry")
        private List<Song> entries;
        public String getId() { return id; }
        public String getName() { return name; }
        public String getOwner() { return owner; }
        public boolean isPublic() { return isPublic; }
        public long getChanged() { return parseIsoToEpoch(changed); }
        public List<Song> getEntries() { return entries != null ? entries : new ArrayList<>(); }
        private long parseIsoToEpoch(String value) {
            try {
                if (value == null || value.isEmpty()) return 0L;
                int dot = value.indexOf('.');
                int z = value.indexOf('Z', dot >= 0 ? dot : 0);
                if (dot >= 0 && z > dot) {
                    String frac = value.substring(dot + 1, z);
                    String ms;
                    if (frac.length() >= 3) ms = frac.substring(0, 3);
                    else if (frac.length() == 2) ms = frac + "0";
                    else if (frac.length() == 1) ms = frac + "00";
                    else ms = "000";
                    value = value.substring(0, dot) + "." + ms + "Z";
                }
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return sdf.parse(value).getTime();
            } catch (Exception ignore) { return 0L; }
        }
    }

    private static class AlbumResponse {
        @SerializedName("subsonic-response")
        private AlbumResponseData response;
        public AlbumResponseData getResponse() { return response; }
    }
    private static class AlbumResponseData {
        private String status;
        private String version;
        private AlbumData album;
        private Error error;
        public boolean isSuccess() { return "ok".equals(status); }
        public AlbumData getAlbum() { return album; }
    }
    private static class SongsResponse {
        @SerializedName("subsonic-response")
        private SongsResponseData response;
        public SongsResponseData getResponse() { return response; }
    }
    private static class SongsResponseData {
        private String status;
        private String version;
        @SerializedName("randomSongs")
        private SongsData songsData;
        private Error error;
        public boolean isSuccess() { return "ok".equals(status); }
        public List<Song> getSongs() { return songsData != null ? songsData.getSongs() : new ArrayList<>(); }
    }
    private static class SongsData {
        @SerializedName("song")
        private List<Song> songs;
        public List<Song> getSongs() { return songs != null ? songs : new ArrayList<>(); }
    }
    private static class AlbumData {
        private String id;
        private String name;
        private String artist;
        @SerializedName("song")
        private List<Song> songs;
        public String getId() { return id; }
        public List<Song> getSongs() { return songs != null ? songs : new ArrayList<>(); }
    }

    public Integer getSongDurationSeconds(String songId) throws IOException {
        if (songId == null || songId.isEmpty()) return null;
        String salt = generateSalt();
        String token = generateToken(password, salt);
        HttpUrl url = getBaseUrlBuilder()
                .addPathSegment("getSong")
                .addQueryParameter("u", username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .addQueryParameter("id", songId)
                .build();
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            String json = response.body().string();
            try {
                com.google.gson.JsonObject root = parseJsonObjectCompat(json);
                if (root == null) return null;
                com.google.gson.JsonObject sr = root.has("subsonic-response") && root.get("subsonic-response").isJsonObject()
                        ? root.getAsJsonObject("subsonic-response") : null;
                if (sr == null) return null;
                if (sr.has("song") && sr.get("song").isJsonObject()) {
                    com.google.gson.JsonObject so = sr.getAsJsonObject("song");
                    if (so.has("duration") && so.get("duration").isJsonPrimitive()) {
                        try { return so.get("duration").getAsInt(); } catch (Exception ignore) {}
                    }
                    // 宽松兜底：尝试读取任意整数字段名中包含"duration"的
                    for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : so.entrySet()) {
                        if (e.getKey() != null && e.getKey().toLowerCase().contains("duration")) {
                            try { return e.getValue().getAsInt(); } catch (Exception ignore) {}
                        }
                    }
                }
            } catch (Exception ignore) {}
            return null;
        }
    }
} 