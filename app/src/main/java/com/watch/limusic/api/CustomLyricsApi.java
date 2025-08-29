package com.watch.limusic.api;

import android.net.Uri;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * 自定义歌词源（实验性）：
 * - URL 模板示例： https://your.domain/lyrics?artist={artist}&title={title}
 * - {artist} 与 {title} 将进行URL编码替换
 * - 响应可为：
 *   1) 纯文本：直接返回LRC；
 *   2) JSON：优先取 syncedLyrics / lrc / lyrics 的字符串字段
 */
public class CustomLyricsApi {
	private static CustomLyricsApi instance;
	private final OkHttpClient client;

	public static CustomLyricsApi getInstance() {
		if (instance == null) instance = new CustomLyricsApi();
		return instance;
	}

	private CustomLyricsApi() {
		HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
		logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
		client = new OkHttpClient.Builder()
				.addInterceptor(logging)
				.build();
	}

	public String fetch(String urlTemplate, String artist, String title) throws IOException {
		if (urlTemplate == null || urlTemplate.trim().isEmpty()) return null;
		if (artist == null) artist = "";
		if (title == null) title = "";
		String encArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8.name());
		String encTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.name());
		String urlStr = urlTemplate.replace("{artist}", encArtist).replace("{title}", encTitle);
		Uri uri = Uri.parse(urlStr);
		if (uri.getScheme() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
			return null;
		}
		Request req = new Request.Builder().url(urlStr).build();
		try (Response resp = client.newCall(req).execute()) {
			if (!resp.isSuccessful() || resp.body() == null) return null;
			String body = resp.body().string();
			if (body == null || body.trim().isEmpty()) return null;
			String ct = resp.header("Content-Type", "");
			if (ct != null && ct.toLowerCase().contains("application/json")) {
				try {
					com.google.gson.JsonObject root = parseJsonObjectCompat(body);
					if (root != null) {
						String val;
						val = getStringField(root, "syncedLyrics"); if (valid(val)) return val;
						val = getStringField(root, "lrc"); if (valid(val)) return val;
						val = getStringField(root, "lyrics"); if (valid(val)) return val;
						for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
							if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
								val = e.getValue().getAsString();
								if (valid(val)) return val;
							}
						}
					}
				} catch (Exception ignore) {}
			}
			// 纯文本或未知类型：若看起来像LRC则直接返回
			if (valid(body)) return body;
			return null;
		}
	}

	private static boolean valid(String text) {
		if (text == null) return false;
		String s = text.trim();
		if (s.isEmpty()) return false;
		// 粗略判定：包含时间标签即认为是LRC；否则也允许返回纯文本歌词（由上层处理为纯文本模式）
		return s.contains("[") && s.contains("]") || s.length() > 0;
	}

	private static com.google.gson.JsonObject parseJsonObjectCompat(String json) {
		try {
			com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
			return parser.parse(json).getAsJsonObject();
		} catch (Throwable t) { return null; }
	}

	private static String getStringField(com.google.gson.JsonObject root, String key) {
		try {
			if (root.has(key) && root.get(key).isJsonPrimitive()) {
				String v = root.get(key).getAsString();
				return (v != null && !v.trim().isEmpty()) ? v : null;
			}
		} catch (Throwable ignore) {}
		return null;
	}
} 