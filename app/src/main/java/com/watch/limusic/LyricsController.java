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
		}
	};

	public LyricsController(Context ctx, PlayerBridge player) {
		this.context = ctx.getApplicationContext();
		this.player = player;
		this.repo = new LyricsRepository(ctx);
		try { context.registerReceiver(uiReceiver, new android.content.IntentFilter("com.watch.limusic.UI_SETTINGS_CHANGED")); } catch (Exception ignore) {}
	}

	public void setUiBridge(UiBridge bridge) { this.uiBridge = bridge; }

	public void ensureInflated(View parent) {
		if (root != null) return;
		ViewStub stub = parent.findViewById(R.id.lyrics_stub);
		if (stub == null) return;
		root = stub.inflate();
		list = root.findViewById(R.id.lyrics_list);
		placeholder = root.findViewById(R.id.lyrics_placeholder);
		layoutManager = new LinearLayoutManager(parent.getContext());
		if (list != null) {
			list.setLayoutManager(layoutManager);
			try { list.setItemAnimator(null); } catch (Exception ignore) {}
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
					if (newState == RecyclerView.SCROLL_STATE_DRAGGING || newState == RecyclerView.SCROLL_STATE_SETTLING) {
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
	}

	public void pause() { paused = true; handler.removeCallbacks(tickRunnable); }
	public void resume() { paused = false; onPlaybackTick(); }

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
				android.view.View v = layoutManager.findViewByPosition(idx);
				if (v == null) {
					layoutManager.scrollToPosition(idx);
					list.postDelayed(() -> {
						android.view.View v2 = layoutManager.findViewByPosition(idx);
						int listH = list.getHeight();
						int itemH = (v2 != null ? v2.getHeight() : 0);
						int offset = Math.max(0, (listH - itemH) / 2);
						try { layoutManager.scrollToPositionWithOffset(idx, offset); } catch (Exception ignore) {}
					}, 16);
				} else {
					int listH = list.getHeight();
					int itemH = v.getHeight();
					int offset = Math.max(0, (listH - itemH) / 2);
					layoutManager.scrollToPositionWithOffset(idx, offset);
				}
			} catch (Exception ignore) {}
		});
	}
} 