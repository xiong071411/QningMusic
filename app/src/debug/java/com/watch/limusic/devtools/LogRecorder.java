package com.watch.limusic.devtools;

import android.content.Context;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LogRecorder {
	private static final String TAG = "LogRecorder";
	private static final AtomicBoolean recording = new AtomicBoolean(false);
	private static volatile Thread worker;
	private static volatile Process proc;
	private static volatile FileOutputStream fos;
	private static volatile File currentFile;

	public static synchronized boolean isRecording() {
		return recording.get();
	}

	public static synchronized boolean start(Context context) {
		if (recording.get()) return false;
		try {
			File dir = new File(context.getExternalFilesDir(null), "logs");
			if (!dir.exists()) { //noinspection ResultOfMethodCallIgnored
				dir.mkdirs();
			}
			String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
			currentFile = new File(dir, "log_" + ts + ".txt.tmp");
			fos = new FileOutputStream(currentFile, true);

			final String pid = String.valueOf(android.os.Process.myPid());
			ProcessBuilder pb;
			try {
				pb = new ProcessBuilder("logcat", "--pid", pid, "-v", "time");
				pb.redirectErrorStream(true);
				proc = pb.start();
			} catch (Throwable t) {
				// 回退：部分设备不支持 --pid，退化为全量但通常仅能读取本应用日志
				try {
					pb = new ProcessBuilder("logcat", "-v", "time");
					pb.redirectErrorStream(true);
					proc = pb.start();
				} catch (Throwable t2) {
					Log.e(TAG, "启动logcat失败", t2);
					closeQuietly(fos);
					fos = null;
					currentFile = null;
					return false;
				}
			}

			recording.set(true);
			worker = new Thread(() -> {
				try (InputStream is = proc.getInputStream(); BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
					String line;
					while (recording.get() && (line = br.readLine()) != null) {
						if (fos != null) {
							fos.write((line + "\n").getBytes());
							fos.flush();
						}
					}
				} catch (Throwable t) {
					Log.w(TAG, "日志读取结束", t);
				} finally {
					cleanupProcess();
				}
			}, "LogRecorder-Worker");
			worker.setDaemon(true);
			worker.start();
			return true;
		} catch (Throwable e) {
			Log.e(TAG, "start failed", e);
			return false;
		}
	}

	public static synchronized File stop(Context context) {
		if (!recording.get()) return null;
		recording.set(false);
		try { if (worker != null) worker.interrupt(); } catch (Throwable ignore) {}
		cleanupProcess();
		closeQuietly(fos);
		fos = null;
		File out = null;
		try {
			if (currentFile != null && currentFile.exists()) {
				String name = currentFile.getName().replace(".tmp", "");
				File finalFile = new File(currentFile.getParentFile(), name);
				boolean ok = currentFile.renameTo(finalFile);
				out = ok ? finalFile : currentFile;
			}
		} catch (Throwable ignore) {}
		currentFile = null;
		return out;
	}

	private static void cleanupProcess() {
		try { if (proc != null) proc.destroy(); } catch (Throwable ignore) {}
		proc = null;
	}

	private static void closeQuietly(java.io.Closeable c) {
		try { if (c != null) c.close(); } catch (Throwable ignore) {}
	}

	private LogRecorder() {}
} 