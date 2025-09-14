package com.watch.limusic.audio;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

public final class AudioLevelBus {
	public static final String ACTION_AUDIO_LEVELS = "com.watch.limusic.AUDIO_LEVELS";
	private static volatile Context app;
	private static final Handler main = new Handler(Looper.getMainLooper());
	private static volatile long lastPostMs = 0L;
	private static volatile int maxFps = 30;

	public static void init(Context applicationContext) {
		app = applicationContext.getApplicationContext();
	}

	public static void setMaxFps(int fps) { maxFps = Math.max(5, Math.min(60, fps)); }
	public static int getMaxFps() { return maxFps; }

	public static void publish(final float[] bars, final boolean isPlaying) {
		final Context c = app;
		if (c == null || bars == null) return;
		long now = System.currentTimeMillis();
		long minInterval = 1000L / Math.max(1, maxFps);
		if (now - lastPostMs < minInterval) return;
		lastPostMs = now;
		final float[] copy = new float[bars.length];
		System.arraycopy(bars, 0, copy, 0, bars.length);
		main.post(new Runnable() {
			@Override public void run() {
				try {
					Intent i = new Intent(ACTION_AUDIO_LEVELS);
					i.putExtra("isPlaying", isPlaying);
					i.putExtra("levels", copy);
					c.sendBroadcast(i);
				} catch (Throwable ignore) {}
			}
		});
	}

	private AudioLevelBus() {}
}
