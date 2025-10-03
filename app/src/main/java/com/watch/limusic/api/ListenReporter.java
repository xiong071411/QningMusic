package com.watch.limusic.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.watch.limusic.model.Song;
import com.watch.limusic.database.MusicDatabase;
import com.watch.limusic.database.SongEntity;
import com.watch.limusic.database.MusicDatabase;
import com.watch.limusic.database.SongEntity;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 轻量听歌上报器：
 * - Basic 认证，用户名/密码与网站一致
 * - 按接口文档 POST /api/listens 上报，失败仅在网络/5xx 重试一次
 * - 未配置或关闭时自动跳过
 *
 * 偏好项：SharedPreferences("listen_report_settings")
 * - enabled (boolean, default false)
 * - base_url (string, e.g. https://your.site)
 * - username (string)
 * - password (string)
 */
public class ListenReporter {
    private static final String TAG = "ListenReporter";

    private static final String PREFS = "listen_report_settings";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_ALLOW_INSECURE = "allow_insecure"; // 仅用于测试环境：忽略主机名校验（不安全）

    private static volatile ListenReporter instance;

    private final Context app;
    private final ExecutorService executor;
    private OkHttpClient httpClient;

    private ListenReporter(Context context) {
        this.app = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .writeTimeout(1500, TimeUnit.MILLISECONDS)
                .callTimeout(2000, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public static ListenReporter getInstance(Context context) {
        if (instance == null) {
            synchronized (ListenReporter.class) {
                if (instance == null) instance = new ListenReporter(context);
            }
        }
        return instance;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return null;
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == '/') n--;
        return s.substring(0, n);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c <= 0x1F) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public void reportAsync(final Song song) {
        if (song == null) return;
        final SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final boolean enabled = sp.getBoolean(KEY_ENABLED, false);
        if (!enabled) return;
        final String baseUrl = trimTrailingSlash(sp.getString(KEY_BASE_URL, ""));
        final String username = sp.getString(KEY_USERNAME, "");
        final String password = sp.getString(KEY_PASSWORD, "");
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            Log.d(TAG, "未配置或配置不完整，跳过上报");
            return;
        }

        executor.execute(new Runnable() { @Override public void run() {
            try {
                final long epochSec = System.currentTimeMillis() / 1000L;
                final int durationSec = computeDurationSecondsSafe(song);
                final StringBuilder sb = new StringBuilder(256);
                sb.append('{');
                sb.append("\"title\":\"").append(jsonEscape(safe(song.getTitle()))).append("\"");
                sb.append(',').append("\"artist\":\"").append(jsonEscape(safe(song.getArtist()))).append("\"");
                sb.append(',').append("\"album\":\"").append(jsonEscape(safe(song.getAlbum()))).append("\"");
                sb.append(',').append("\"source\":\"watch\"");
                sb.append(',').append("\"started_at\":").append(epochSec);
                if (durationSec > 0) {
                    sb.append(',').append("\"duration_sec\":").append(durationSec);
                }
                sb.append(',').append("\"external_id\":\"").append(jsonEscape(safe(song.getId()))).append("\"");
                sb.append('}');
                final String body = sb.toString();

                final String url = baseUrl + "/api/listens";
                final String auth = Credentials.basic(username, password);
                final Request req = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", auth)
                        .addHeader("Accept", "application/json")
                        .post(RequestBody.create(body, JSON))
                        .build();

                final boolean allowInsecure = sp.getBoolean(KEY_ALLOW_INSECURE, false);
                final OkHttpClient clientToUse;
                if (allowInsecure) {
                    String host = null;
                    try {
                        java.net.URI u = new java.net.URI(baseUrl);
                        host = u.getHost();
                    } catch (Exception ignore) {}
                    final String finalHost = host;
                    clientToUse = new OkHttpClient.Builder()
                            .connectTimeout(1500, TimeUnit.MILLISECONDS)
                            .readTimeout(1500, TimeUnit.MILLISECONDS)
                            .writeTimeout(1500, TimeUnit.MILLISECONDS)
                            .callTimeout(2000, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(true)
                            .hostnameVerifier((hostname, session) -> finalHost != null && hostname != null && hostname.equalsIgnoreCase(finalHost))
                            .build();
                    Log.w(TAG, "已启用不安全HTTPS（忽略主机名校验）: host=" + finalHost);
                } else {
                    clientToUse = httpClient;
                }

                performOnceWithRetry(clientToUse, req);
            } catch (Throwable t) {
                Log.w(TAG, "构建上报请求失败(忽略)", t);
            }
        }});
    }

    // 优先从本地数据库读取秒级时长；若无则根据 Song.getDuration() 猜测
    private int computeDurationSecondsSafe(Song song) {
        try {
            if (song != null && song.getId() != null) {
                SongEntity se = MusicDatabase.getInstance(app).songDao().getSongById(song.getId());
                if (se != null && se.getDuration() > 0) return se.getDuration();
            }
        } catch (Throwable ignore) {}
        try {
            int d = song != null ? song.getDuration() : 0;
            if (d >= 1000) {
                // 避免异常大值：限制 24h 上限
                int capped = Math.min(d, 24 * 60 * 60 * 1000);
                return Math.max(1, capped / 1000);
            }
            if (d > 0 && d < 1000) return d;
        } catch (Throwable ignore) {}
        return 0;
    }

    // 计算秒级时长：统一入口（已包含“优先DB、再猜测”的逻辑），避免重复定义

    private void performOnceWithRetry(OkHttpClient client, Request req) {
        Response resp = null;
        try {
            Call call = client.newCall(req);
            resp = call.execute();
            if (resp.isSuccessful()) {
                Log.d(TAG, "上报成功");
                return;
            }
            int code = resp.code();
            String bodyStr = null;
            try { bodyStr = resp.body() != null ? resp.body().string() : null; } catch (Exception ignore) {}
            Log.w(TAG, "上报失败，code=" + code + (bodyStr != null ? (", body=" + bodyStr) : ""));
            if (code >= 500) {
                try { Thread.sleep(120); } catch (InterruptedException ignored) {}
                retry(client, req);
            }
        } catch (IOException ioe) {
            Log.w(TAG, "网络异常，上报失败，准备重试一次", ioe);
            retry(client, req);
        } finally {
            if (resp != null) try { resp.close(); } catch (Exception ignored) {}
        }
    }

    private void retry(OkHttpClient client, Request req) {
        Response r2 = null;
        try {
            r2 = client.newCall(req).execute();
            if (r2.isSuccessful()) {
                Log.d(TAG, "上报成功(重试)");
            } else {
                Log.w(TAG, "重试仍失败，code=" + r2.code());
            }
        } catch (IOException e) {
            Log.w(TAG, "重试出现网络异常，放弃", e);
        } finally {
            if (r2 != null) try { r2.close(); } catch (Exception ignored) {}
        }
    }
}


