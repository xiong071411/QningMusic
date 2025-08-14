package com.watch.limusic.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class NavidromeClient {
    // 公共常量，确保与NavidromeApi中的值一致
    public static final String CLIENT_NAME = "LiMusic";
    public static final String API_VERSION = "1.16.1";
    
    private static final String PREF_NAME = "navidrome_prefs";
    private static final String PREF_SERVER = "server_url";
    private static final String PREF_USERNAME = "username";
    private static final String PREF_PASSWORD = "password";

    private static NavidromeClient instance;
    private final NavidromeService service;
    private final SharedPreferences prefs;
    private final String baseUrl;
    private final String username;
    private final String password;

    private NavidromeClient(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        baseUrl = prefs.getString(PREF_SERVER, "");
        username = prefs.getString(PREF_USERNAME, "");
        password = prefs.getString(PREF_PASSWORD, "");

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new AuthInterceptor(username, password))
                .addInterceptor(new UserAgentInterceptor())
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl + "/rest/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        service = retrofit.create(NavidromeService.class);
    }

    public static synchronized NavidromeClient getInstance(Context context) {
        if (instance == null) {
            instance = new NavidromeClient(context.getApplicationContext());
        }
        return instance;
    }

    public NavidromeService getService() {
        return service;
    }

    public void saveCredentials(String serverUrl, String username, String password) {
        prefs.edit()
                .putString(PREF_SERVER, serverUrl)
                .putString(PREF_USERNAME, username)
                .putString(PREF_PASSWORD, password)
                .apply();
    }

    public String getStreamUrl(String id) {
        return baseUrl + "/rest/stream.view" +
                "?id=" + id +
                "&u=" + username +
                "&p=" + password +
                "&c=" + CLIENT_NAME +
                "&v=" + API_VERSION +
                "&f=json";
    }

    public String getCoverArtUrl(String id) {
        return baseUrl + "/rest/getCoverArt.view" +
                "?id=" + id +
                "&u=" + username +
                "&p=" + password +
                "&c=" + CLIENT_NAME +
                "&v=" + API_VERSION +
                "&f=json";
    }

    private static class AuthInterceptor implements Interceptor {
        private final String credentials;

        AuthInterceptor(String username, String password) {
            String auth = username + ":" + password;
            credentials = "Basic " + Base64.encodeToString(auth.getBytes(), Base64.NO_WRAP);
        }

        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Request authenticatedRequest = request.newBuilder()
                    .header("Authorization", credentials)
                    .build();
            return chain.proceed(authenticatedRequest);
        }
    }

    private static class UserAgentInterceptor implements Interceptor {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request originalRequest = chain.request();
            Request requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", CLIENT_NAME)
                    .build();
            return chain.proceed(requestWithUserAgent);
        }
    }
} 