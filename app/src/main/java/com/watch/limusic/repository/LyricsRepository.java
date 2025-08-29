package com.watch.limusic.repository;

import android.content.Context;
import android.content.SharedPreferences;

import com.watch.limusic.api.CustomLyricsApi;
import com.watch.limusic.api.NavidromeApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class LyricsRepository {
	private final Context context;

	public LyricsRepository(Context context) {
		this.context = context.getApplicationContext();
	}

	public String loadLyricsText(String songId, String artist, String title) {
		if (songId == null || songId.isEmpty()) return null;
		SharedPreferences sp = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE);
		boolean lowPower = sp.getBoolean("low_power_mode_enabled", false);
		if (lowPower) return null;
		boolean customEnabled = sp.getBoolean("custom_lyrics_enabled", false);
		String customUrl = sp.getString("custom_lyrics_url", "");

		// 1) 本地缓存
		String local = readFromCache(songId);
		if (local != null && !local.isEmpty()) return local;
		// 2) 下载目录同名 .lrc（若存在）
		String fromDownloads = readFromDownloads(songId);
		if (fromDownloads != null && !fromDownloads.isEmpty()) {
			writeToCache(songId, fromDownloads);
			return fromDownloads;
		}
		// 3) 自定义歌词源（若启用且URL有效）
		if (customEnabled && customUrl != null && !customUrl.trim().isEmpty()) {
			try {
				String lrc = CustomLyricsApi.getInstance().fetch(customUrl, artist, title);
				if (lrc != null && !lrc.trim().isEmpty()) {
					writeToCache(songId, lrc);
					return lrc;
				}
			} catch (Exception ignore) {}
		}
		// 4) 回退到 Navidrome 服务器
		try {
			String remote = fetchFromServer(songId, artist, title);
			if (remote != null && !remote.isEmpty()) {
				writeToCache(songId, remote);
				return remote;
			}
		} catch (Exception ignore) {}
		return null;
	}

	private String readFromCache(String songId) {
		File f = new File(getLyricsCacheDir(), songId + ".lrc");
		if (!f.exists() || f.length() == 0) return null;
		try (FileInputStream in = new FileInputStream(f)) {
			byte[] data = new byte[(int) f.length()];
			int n = in.read(data);
			if (n > 0) return new String(data, 0, n, java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException ignore) {}
		return null;
	}

	private String readFromDownloads(String songId) {
		File downloadDir = new File(context.getExternalFilesDir(null), "downloads");
		File songsDir = new File(downloadDir, "songs");
		File f = new File(songsDir, songId + ".lrc");
		if (!f.exists() || f.length() == 0) return null;
		try (FileInputStream in = new FileInputStream(f)) {
			byte[] data = new byte[(int) f.length()];
			int n = in.read(data);
			if (n > 0) return new String(data, 0, n, java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException ignore) {}
		return null;
	}

	private void writeToCache(String songId, String text) {
		try {
			File dir = getLyricsCacheDir();
			if (!dir.exists()) dir.mkdirs();
			File f = new File(dir, songId + ".lrc");
			try (FileOutputStream out = new FileOutputStream(f)) {
				byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				out.write(data);
			}
		} catch (IOException ignore) {}
	}

	private File getLyricsCacheDir() {
		File dir = new File(context.getExternalFilesDir(null), "downloads/lyrics");
		return dir;
	}

	private String fetchFromServer(String songId, String artist, String title) throws IOException {
		if ((artist == null || artist.isEmpty()) && (title == null || title.isEmpty())) return null;
		try {
			String txt = NavidromeApi.getInstance(context).getLyrics(artist, title);
			return (txt != null && !txt.trim().isEmpty()) ? txt : null;
		} catch (Exception e) {
			return null;
		}
	}
}