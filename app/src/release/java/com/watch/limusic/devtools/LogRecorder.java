package com.watch.limusic.devtools;

import android.content.Context;
import java.io.File;

public final class LogRecorder {
	public static boolean isRecording() { return false; }
	public static boolean start(Context context) { return false; }
	public static File stop(Context context) { return null; }
	private LogRecorder() {}
} 