package com.watch.limusic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewStub;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.adapter.LyricsAdapter;
import com.watch.limusic.repository.LyricsRepository;
import com.watch.limusic.util.LyricsParser;

import java.util.List;

public class LyricsController {
	public interface PlayerBridge {
		long getCurrentPosition();
		long getDuration();
		boolean isPlaying();
		void seekTo(long positionMs);
		String getCurrentSongId();
		String getCurrentArtist();
		String getCurrentTitle();
		int getAudioSessionId();
	}

	public interface UiBridge {
		void requestSwitchToMainPage();
	}

	private final Context context;
	private final PlayerBridge player;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final LyricsRepository repo;
	private UiBridge uiBridge;

	private View root;
	private RecyclerView list;
	private TextView placeholder;
	private LyricsAdapter adapter;
	private LinearLayoutManager layoutManager;
	private LyricsParser.Result parsed;
	private int currentIndex = -1;
	private boolean userBrowsing = false;
	private long lastUserInteractAt = 0L;
	private boolean paused = false;

	// 新增：可视化视图引用与设置缓存
	private com.watch.limusic.view.AudioBarsVisualizerView visualizerView;
	private boolean visualizerEnabled = false;
	private int visualizerAlphaPercent = 40;
	private boolean lowPowerMode = false;

	private final android.content.BroadcastReceiver uiReceiver = new android.content.BroadcastReceiver() {
		@Override public void onReceive(android.content.Context context, android.content.Intent intent) {
			if (!"com.watch.limusic.UI_SETTINGS_CHANGED".equals(intent.getAction())) return;
			String what = intent.getStringExtra("what");
			if (adapter != null && (what == null || "lyric_size".equals(what))) {
				adapter.reloadSizes();
			}
			if (what != null && "lyric_source".equals(what)) {
				loadLyricsIfNeeded();
			}
			// 音频可视化设置变化
			if (what != null && ("visualizer".equals(what) || "player_bg".equals(what))) {
				refreshVisualizerSettings();
			}
		}
	};

	// 新增：监听播放状态/会话ID广播（用于控制可视化视图）
	private final android.content.BroadcastReceiver playbackReceiver = new android.content.BroadcastReceiver() {
		@Override public void onReceive(android.content.Context ctx, android.content.Intent intent) {
			String act = intent.getAction();
			if ("com.watch.limusic.PLAYBACK_STATE_CHANGED".equals(act)) {
				boolean isPlaying = intent.getBooleanExtra("isPlaying", false);
				if (visualizerView != null) visualizerView.setPlaying(isPlaying);
				// PCM抽头已提供数据，不再绑定Visualizer会话
			} else if ("com.watch.limusic.AUDIO_SESSION_CHANGED".equals(act)) {
				// 忽略：避免在不支持的设备上反复尝试初始化Visualizer
			}
		}
	};

	// 新增：订阅PCM抽头广播
	private final android.content.BroadcastReceiver levelsReceiver = new android.content.BroadcastReceiver() {
		@Override public void onReceive(android.content.Context ctx, android.content.Intent intent) {
			if (!"com.watch.limusic.AUDIO_LEVELS".equals(intent.getAction())) return;
			if (visualizerView == null || !visualizerEnabled) return;
			float[] levels = intent.getFloatArrayExtra("levels");
			if (levels != null) {
				try { visualizerView.setLevels(levels); } catch (Throwable ignore) {}
			}
		}
	};

	public LyricsController(Context ctx, PlayerBridge player) {
		this.context = ctx.getApplicationContext();
		this.player = player;
		this.repo = new LyricsRepository(ctx);
		try { context.registerReceiver(uiReceiver, new android.content.IntentFilter("com.watch.limusic.UI_SETTINGS_CHANGED")); } catch (Exception ignore) {}
		try {
			android.content.IntentFilter f = new android.content.IntentFilter();
			f.addAction("com.watch.limusic.PLAYBACK_STATE_CHANGED");
			f.addAction("com.watch.limusic.AUDIO_SESSION_CHANGED");
			context.registerReceiver(playbackReceiver, f);
		} catch (Exception ignore) {}
		try { context.registerReceiver(levelsReceiver, new android.content.IntentFilter("com.watch.limusic.AUDIO_LEVELS")); } catch (Exception ignore) {}
	}

	public void setUiBridge(UiBridge bridge) { this.uiBridge = bridge; }

	public void ensureInflated(View parent) {
		if (root != null) return;
		ViewStub stub = parent.findViewById(R.id.lyrics_stub);
		if (stub == null) return;
		root = stub.inflate();
		list = root.findViewById(R.id.lyrics_list);
		placeholder = root.findViewById(R.id.lyrics_placeholder);
		// 新增：获取可视化视图引用
		try { visualizerView = root.findViewById(R.id.audio_bars_visualizer); } catch (Exception ignore) {}
		refreshVisualizerSettings();
		// 使用PCM抽头方案，不再主动绑定Visualizer会话（避免设备拒绝导致反复报错）
		layoutManager = new LinearLayoutManager(parent.getContext());
		if (list != null) {
			list.setLayoutManager(layoutManager);
			try { list.setItemAnimator(null); } catch (Exception ignore) {}
			// 新增：固定大小与缓存以降低滚动CPU
			try { list.setHasFixedSize(true); } catch (Exception ignore) {}
			try { list.setItemViewCacheSize(8); } catch (Exception ignore) {}
			try { list.getRecycledViewPool().setMaxRecycledViews(0, 32); } catch (Exception ignore) {}
			try {
				list.setOnTouchListener(new com.watch.limusic.util.SwipeGestureListener(parent.getContext()) {
					@Override public void onSwipeRight() { if (uiBridge != null) uiBridge.requestSwitchToMainPage(); }
					@Override public void onSwipeLeft() {}
					@Override public void onLongPress() {}
				});
			} catch (Exception ignore) {}
			// 监听滚动状态，用户手势期间暂停自动居中
							list.addOnScrollListener(new RecyclerView.OnScrollListener() {
					@Override public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
						if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
							userBrowsing = true;
							lastUserInteractAt = System.currentTimeMillis();
						} else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
							// 空闲时不立即抢回控制，交由tickRunnable在超时后恢复
							lastUserInteractAt = System.currentTimeMillis();
						}
					}
				});
		}
		try {
			root.setOnTouchListener(new com.watch.limusic.util.SwipeGestureListener(parent.getContext()) {
				@Override public void onSwipeRight() { if (uiBridge != null) uiBridge.requestSwitchToMainPage(); }
				@Override public void onSwipeLeft() {}
				@Override public void onLongPress() {}
			});
		} catch (Exception ignore) {}
	}

	private void refreshVisualizerSettings() {
		SharedPreferences sp = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE);
		boolean enabled = sp.getBoolean("visualizer_enabled", false);
		int alpha = sp.getInt("visualizer_alpha", 40);
		boolean low = sp.getBoolean("low_power_mode_enabled", false);
		int style = Math.max(0, Math.min(2, sp.getInt("visualizer_style", 1)));
		visualizerEnabled = enabled && !low;
		visualizerAlphaPercent = Math.max(0, Math.min(100, alpha));
		lowPowerMode = low;
		if (visualizerView != null) {
			visualizerView.setEnabledBySetting(visualizerEnabled);
			visualizerView.setAlphaPercent(visualizerAlphaPercent);
			visualizerView.setLowPowerMode(lowPowerMode);
			try { visualizerView.setRenderStyle(style); } catch (Throwable ignore) {}
			visualizerView.setVisibleOnPage(true); // 歌词页可见后才会调用
			visualizerView.setPlaying(player != null && player.isPlaying());
		}
	}

	public void loadLyricsIfNeeded() {
		SharedPreferences sp = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE);
		boolean lowPower = sp.getBoolean("low_power_mode_enabled", false);
		if (lowPower) {
			showDisabled();
			return;
		}
		final String songId = player.getCurrentSongId();
		final String artist = player.getCurrentArtist();
		final String title = player.getCurrentTitle();
		new Thread(() -> {
			String text = repo.loadLyricsText(songId, artist, title);
			handler.post(() -> applyLyricsText(text));
		}).start();
	}

	private void applyLyricsText(String text) {
		if (text == null || text.trim().isEmpty()) {
			showPlaceholder();
			parsed = null;
			adapter = null;
			return;
		}
		parsed = com.watch.limusic.util.LyricsParser.parse(text);
		List<com.watch.limusic.util.LyricsParser.Line> lines = parsed.lines;
		if (lines == null || lines.isEmpty()) {
			java.util.ArrayList<com.watch.limusic.util.LyricsParser.Line> plain = new java.util.ArrayList<>();
			for (String row : text.split("\n")) {
				if (row == null) continue;
				String s = row.trim();
				if (s.isEmpty()) continue;
				plain.add(new com.watch.limusic.util.LyricsParser.Line(-1, s));
			}
			lines = plain;
			parsed = new com.watch.limusic.util.LyricsParser.Result(lines, 0L);
		}
		adapter = new com.watch.limusic.adapter.LyricsAdapter(context, lines);
		adapter.setOnLineClickListener((pos, start) -> {
			if (start >= 0 && player != null) {
				long dur = 0L;
				boolean allow = true;
				try { dur = Math.max(0, player.getDuration()); } catch (Throwable ignore) {}
				// 当时长未知或不可寻址时，放弃立即seek（交给UI的pending逻辑或提示）
				if (dur <= 0) {
					allow = false;
				}
				if (allow) {
					player.seekTo(start);
					userBrowsing = false;
					scheduleNextTick();
				} else {
					try { android.widget.Toast.makeText(context, "当前曲目暂不支持拖动", android.widget.Toast.LENGTH_SHORT).show(); } catch (Exception ignore) {}
				}
			}
		});
		if (list != null) list.setAdapter(adapter);
		if (placeholder != null) placeholder.setVisibility(View.GONE);
		if (list != null) list.setVisibility(View.VISIBLE);
		currentIndex = -1;
		scheduleNextTick();
	}

	private void showPlaceholder() {
		if (placeholder != null) {
			placeholder.setText(R.string.no_lyrics);
			placeholder.setVisibility(View.VISIBLE);
		}
		if (list != null) list.setVisibility(View.GONE);
	}

	private void showDisabled() {
		if (placeholder != null) {
			placeholder.setText(R.string.lyrics_disabled_low_power);
			placeholder.setVisibility(View.VISIBLE);
		}
		if (list != null) list.setVisibility(View.GONE);
		// 省电模式：关闭可视化
		if (visualizerView != null) {
			visualizerView.setEnabledBySetting(false);
		}
	}

	public void pause() { paused = true; handler.removeCallbacks(tickRunnable); if (visualizerView != null) visualizerView.setVisibleOnPage(false); try { context.unregisterReceiver(levelsReceiver); } catch (Exception ignore) {} }
	public void resume() { paused = false; onPlaybackTick(); if (visualizerView != null) visualizerView.setVisibleOnPage(true); refreshVisualizerSettings(); try { context.registerReceiver(levelsReceiver, new android.content.IntentFilter("com.watch.limusic.AUDIO_LEVELS")); } catch (Exception ignore) {} }

	public void onPlaybackTick() { scheduleNextTick(); }

	private void scheduleNextTick() {
		if (paused) return;
		handler.removeCallbacks(tickRunnable);
		if (adapter == null || parsed == null || parsed.lines == null || parsed.lines.isEmpty()) return;
		boolean hasTiming = false;
		for (com.watch.limusic.util.LyricsParser.Line l : parsed.lines) { if (l.startMs >= 0) { hasTiming = true; break; } }
		if (!hasTiming) return;
		long nowPos = Math.max(0, player.getCurrentPosition());
		int idx = com.watch.limusic.util.LyricsParser.findLineIndex(parsed.lines, nowPos, parsed.offsetMs);
		// 先计算当前索引的下一行剩余时间，供跑马灯使用
		long nextDelay = 800L;
		if (idx + 1 < parsed.lines.size()) {
			long nextStart = parsed.lines.get(idx + 1).startMs - parsed.offsetMs;
			long cur = player.getCurrentPosition();
			nextDelay = Math.max(50L, nextStart - cur);
		}
		if (adapter != null) {
			adapter.setScrollWindowMs(nextDelay);
		}
		if (idx != currentIndex) {
			currentIndex = idx;
			adapter.setCurrentIndex(idx);
			// 用户正在浏览时不自动回中，避免打断手势
			if (!userBrowsing) {
				centerTo(idx);
				// 二次精确居中：等待一帧后再校正一次，保证放大/内边距变化后的精确居中
				handler.postDelayed(new Runnable() { @Override public void run() { centerTo(idx); } }, 16);
			}
		}
		long heartbeat = 500L;
		handler.postDelayed(tickRunnable, Math.min(heartbeat, nextDelay));
	}

	private final Runnable tickRunnable = new Runnable() {
		@Override public void run() {
			if (paused) return;
			if (adapter == null || parsed == null) return;
			if (userBrowsing) {
				if (System.currentTimeMillis() - lastUserInteractAt >= 4000L) {
					userBrowsing = false;
					centerTo(currentIndex);
				}
			}
			scheduleNextTick();
		}
	};

	private void centerTo(final int idx) {
		if (list == null || layoutManager == null || idx < 0) return;
		list.post(() -> {
			try {
				int listH = list.getHeight();
				int itemH = 0;
				try { if (adapter != null) itemH = Math.max(0, adapter.getPredictedCurrentItemHeightPx()); } catch (Throwable ignore) {}
				int offset = Math.max(0, (listH - itemH) / 2);
				boolean smooth = false;
				try {
					SharedPreferences sp = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE);
					boolean enabled = sp.getBoolean("lyric_smooth_enabled", true);
					boolean low = sp.getBoolean("low_power_mode_enabled", false);
					smooth = enabled && !low;
				} catch (Throwable ignore) {}
				if (smooth) {
					int first = layoutManager.findFirstVisibleItemPosition();
					int last = layoutManager.findLastVisibleItemPosition();
					if (first >= 0 && last >= first && idx >= first && idx <= last) {
						View v = layoutManager.findViewByPosition(idx);
						if (v != null) {
							int topPad = list.getPaddingTop();
							int bottomPad = list.getPaddingBottom();
							int avail = Math.max(0, listH - topPad - bottomPad);
							int h = (itemH > 0 ? itemH : Math.max(0, v.getHeight()));
							int targetTop = topPad + Math.max(0, (avail - h) / 2);
							int dy = v.getTop() - targetTop;
							if (Math.abs(dy) > 2) {
								try { list.smoothScrollBy(0, dy); } catch (Throwable ignore) {}
								return;
							}
						}
					}
				}
				layoutManager.scrollToPositionWithOffset(idx, offset);
			} catch (Exception ignore) {}
		});
	}
} 