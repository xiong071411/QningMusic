package com.watch.limusic.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class LyricsParser {
	public static class Line {
		public final long startMs;
		public final String text;
		public Line(long startMs, String text) {
			this.startMs = startMs;
			this.text = text == null ? "" : text;
		}
	}

	public static class Result {
		public final List<Line> lines;
		public final long offsetMs;
		public Result(List<Line> lines, long offsetMs) {
			this.lines = lines;
			this.offsetMs = offsetMs;
		}
	}

	public static Result parse(String lrcText) {
		if (lrcText == null) return new Result(Collections.emptyList(), 0);
		String[] rows = lrcText.split("\n");
		List<Line> list = new ArrayList<>();
		long globalOffset = 0L;
		boolean anyTimed = false;
		for (String raw : rows) {
			if (raw == null) continue;
			String line = raw.trim();
			if (line.isEmpty()) continue;
			// [offset:100]
			if (line.startsWith("[offset:") && line.endsWith("]")) {
				try {
					String v = line.substring(8, line.length() - 1);
					globalOffset = Long.parseLong(v);
				} catch (Exception ignore) {}
				continue;
			}
			int idx = 0;
			List<Long> stamps = new ArrayList<>();
			while (idx < line.length() && line.charAt(idx) == '[') {
				int end = line.indexOf(']', idx + 1);
				if (end <= idx) break;
				String tag = line.substring(idx + 1, end);
				Long t = parseTimestamp(tag);
				if (t != null) { stamps.add(t); anyTimed = true; }
				idx = end + 1;
			}
			String text = line.substring(Math.min(idx, line.length())).trim();
			if (stamps.isEmpty()) continue;
			for (Long t : stamps) list.add(new Line(t, text));
		}
		Collections.sort(list, Comparator.comparingLong(a -> a.startMs));
		return new Result(list, globalOffset);
	}

	private static Long parseTimestamp(String tag) {
		// 支持 [mm:ss] [mm:ss.S] [mm:ss.SS] [mm:ss.SSS] 以及 [hh:mm:ss] 等
		try {
			String[] parts = tag.split(":");
			int h = 0, m, s;
			int start = 0;
			if (parts.length == 3) { h = Integer.parseInt(parts[0]); start = 1; }
			m = Integer.parseInt(parts[start]);
			String secPart = parts[start + 1];
			int dot = secPart.indexOf('.') >= 0 ? secPart.indexOf('.') : secPart.indexOf(',');
			if (dot >= 0) {
				int sec = Integer.parseInt(secPart.substring(0, dot));
				String frac = secPart.substring(dot + 1);
				int ms;
				if (frac.length() >= 3) ms = Integer.parseInt(frac.substring(0, 3));
				else if (frac.length() == 2) ms = Integer.parseInt(frac) * 10;
				else if (frac.length() == 1) ms = Integer.parseInt(frac) * 100;
				else ms = 0;
				long total = (h * 3600L + m * 60L + sec) * 1000L + ms;
				return total;
			} else {
				int sec = Integer.parseInt(secPart);
				long total = (h * 3600L + m * 60L + sec) * 1000L;
				return total;
			}
		} catch (Exception e) {
			return null;
		}
	}

	public static int findLineIndex(List<Line> lines, long positionMs, long offsetMs) {
		if (lines == null || lines.isEmpty()) return -1;
		long t = Math.max(0, positionMs + offsetMs);
		int lo = 0, hi = lines.size() - 1, ans = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			long ms = lines.get(mid).startMs;
			if (ms <= t) { ans = mid; lo = mid + 1; }
			else { hi = mid - 1; }
		}
		return ans;
	}
} 