package com.watch.limusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.audiofx.Visualizer;
import android.util.AttributeSet;
import android.view.View;

/**
 * 轻量柱状可视化视图（手表优先，低功耗）
 * - 优先使用外部数据驱动（PCM抽头 levels），否则回退 Visualizer 绑定到 audioSessionId
 * - 初始化或运行失败时自动降级为拟真模式（基于随机抖动 + 指数平滑）
 * - 仅在"可见 + 设置开启 + 播放中"时刷新
 */
public class AudioBarsVisualizerView extends View {
	private static final int DEFAULT_BAR_COUNT = 16; // 降耗：默认柱数降低
	private static final int FRAME_INTERVAL_MS = 33; // 约30FPS
	private static final float DECAY_PER_FRAME = 0.08f; // 暂停或静音时的衰减速度
	private static final float SMOOTH_FACTOR = 0.35f; // 指数平滑系数（0-1）

	private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private int barCount = DEFAULT_BAR_COUNT;
	private float[] barLevels = new float[barCount]; // 0..1
	private float[] barTargets = new float[barCount];

	private volatile boolean enabledBySetting = false;
	private volatile boolean visibleOnPage = false;
	private volatile boolean isPlaying = false;
	private volatile boolean lowPowerMode = false;

	private int audioSessionId = 0;
	private Visualizer visualizer;
	private boolean visualizerWorking = false;
	private boolean useFakeMode = false;
	private boolean useExternalLevels = false;

	private final Runnable frameTick = new Runnable() {
		@Override public void run() {
			if (!shouldRender()) return;
			// 更新数据
			updateLevels();
			// 请求绘制
			try { postInvalidateOnAnimation(); } catch (Throwable ignore) { invalidate(); }
			// 下一帧
			int interval = lowPowerMode ? (FRAME_INTERVAL_MS * 2) : FRAME_INTERVAL_MS; // 省电降帧
			removeCallbacks(this);
			postDelayed(this, interval);
		}
	};

	public AudioBarsVisualizerView(Context context) { super(context); init(); }
	public AudioBarsVisualizerView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
	public AudioBarsVisualizerView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

	private void init() {
		barPaint.setStyle(Paint.Style.FILL);
		barPaint.setColor(0xFFFFFFFF); // 默认白色，透明度由View alpha控制
		setAlpha(0.4f); // 默认40%透明
		setWillNotDraw(false);
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		releaseVisualizer();
		removeCallbacks(frameTick);
	}

	// ============== 外部控制接口 ==============
	public void setEnabledBySetting(boolean enabled) {
		this.enabledBySetting = enabled;
		updateRunningState();
	}
	public void setVisibleOnPage(boolean visible) {
		this.visibleOnPage = visible;
		updateRunningState();
	}
	public void setPlaying(boolean playing) {
		this.isPlaying = playing;
		updateRunningState();
	}
	public void setLowPowerMode(boolean low) {
		this.lowPowerMode = low;
		updateRunningState();
	}
	public void setAlphaPercent(int percent) {
		int p = Math.max(0, Math.min(100, percent));
		setAlpha(p / 100f);
		invalidate();
	}
	public void setBarCount(int count) {
		int c = Math.max(10, Math.min(48, count));
		if (c == this.barCount) return;
		this.barCount = c;
		this.barLevels = new float[c];
		this.barTargets = new float[c];
		invalidate();
	}

	// 新增：接收外部可视化数据
	public void setLevels(float[] levels) {
		if (levels == null) return;
		useExternalLevels = true;
		// 切换到外部驱动时释放Visualizer，避免额外耗电
		releaseVisualizer();
		visualizerWorking = false;
		int n = Math.min(levels.length, barLevels.length);
		for (int i = 0; i < n; i++) {
			float target = levels[i];
			barTargets[i] = clamp01(target);
		}
		// 若未在渲染循环中，触发一次绘制
		try { postInvalidateOnAnimation(); } catch (Throwable ignore) { invalidate(); }
	}

	public void bindAudioSession(int sessionId) {
		// 若已由外部levels驱动，忽略任何会话绑定，彻底避免Visualizer初始化报错
		if (useExternalLevels) return;
		if (sessionId <= 0) {
			releaseVisualizer();
			visualizerWorking = false;
			useFakeMode = true;
			return;
		}
		if (this.audioSessionId == sessionId && visualizerWorking) return;
		this.audioSessionId = sessionId;
		setupVisualizer();
	}

	// ============== 内部：运行状态与数据更新 ==============
	private boolean shouldRender() {
		return enabledBySetting && visibleOnPage && (isPlaying || hasEnergy()) && getWidth() > 0 && getHeight() > 0;
	}

	private boolean hasEnergy() {
		for (float v : barLevels) { if (v > 0.02f) return true; }
		return false;
	}

	private void updateRunningState() {
		boolean wantRun = enabledBySetting && visibleOnPage && (isPlaying || hasEnergy());
		if (wantRun) {
			removeCallbacks(frameTick);
			post(frameTick);
		} else {
			removeCallbacks(frameTick);
			// 若不需要渲染，尝试衰减至静止
			if (!isPlaying) {
				decayAll();
				invalidate();
			}
		}
		// 根据条件决定是否启用Visualizer（仅当未使用外部levels时）
		if (!useExternalLevels) {
			if (enabledBySetting && visibleOnPage && isPlaying) {
				if (!visualizerWorking) setupVisualizer();
			} else {
				releaseVisualizer();
			}
		}
		// 可见性：不开启时直接隐藏
		setVisibility(enabledBySetting ? VISIBLE : GONE);
	}

	private void decayAll() {
		for (int i = 0; i < barLevels.length; i++) {
			barLevels[i] = Math.max(0f, barLevels[i] - DECAY_PER_FRAME);
		}
	}

	private void updateLevels() {
		if (useExternalLevels) {
			// 外部levels已经写入barTargets，这里仅做平滑靠近
			for (int i = 0; i < barLevels.length; i++) {
				float target = barTargets[i];
				float cur = barLevels[i];
				float next = cur + (target - cur) * SMOOTH_FACTOR;
				barLevels[i] = clamp01(next);
			}
		} else if (visualizerWorking && !useFakeMode) {
			for (int i = 0; i < barLevels.length; i++) {
				float target = barTargets[i];
				float cur = barLevels[i];
				float next = cur + (target - cur) * SMOOTH_FACTOR;
				barLevels[i] = clamp01(next);
			}
		} else {
			// 拟真模式：缓慢随机抖动
			for (int i = 0; i < barLevels.length; i++) {
				float target = (float) (Math.random() * 0.9 + 0.1);
				if (!isPlaying) target = 0f;
				barLevels[i] = clamp01(barLevels[i] + (target - barLevels[i]) * SMOOTH_FACTOR * 0.6f);
			}
		}
		if (!isPlaying) decayAll();
	}

	private float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

	private void setupVisualizer() {
		releaseVisualizer();
		useFakeMode = false;
		visualizerWorking = false;
		try {
			if (audioSessionId <= 0) { useFakeMode = true; return; }
			visualizer = new Visualizer(audioSessionId);
			visualizer.setEnabled(false);
			int rate = Visualizer.getMaxCaptureRate();
			int desiredRate = lowPowerMode ? rate / 4 : rate / 2;
			int captureSize = chooseCaptureSize();
			visualizer.setCaptureSize(captureSize);
			visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
				@Override public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
					if (waveform == null || waveform.length == 0) return;
					int step = Math.max(1, waveform.length / barTargets.length);
					for (int i = 0; i < barTargets.length; i++) {
						int idx = Math.min(waveform.length - 1, i * step);
						float sample = (waveform[idx] & 0xFF) / 128f - 1f;
						float amp = Math.abs(sample);
						barTargets[i] = clamp01(amp);
					}
				}
				@Override public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {}
			}, desiredRate, true, false);
			visualizer.setEnabled(true);
			visualizerWorking = true;
		} catch (Throwable t) {
			useFakeMode = true;
			visualizerWorking = false;
			releaseVisualizer();
		}
	}

	private int chooseCaptureSize() {
		try {
			int[] range = Visualizer.getCaptureSizeRange();
			int min = range != null && range.length > 0 ? range[0] : 128;
			int max = range != null && range.length > 1 ? range[1] : 1024;
			int want = Math.min(max, Math.max(min, 512));
			return want;
		} catch (Throwable ignore) { return 256; }
	}

	private void releaseVisualizer() {
		try {
			if (visualizer != null) { visualizer.setEnabled(false); visualizer.release(); }
		} catch (Throwable ignore) {}
		visualizer = null;
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		if (!enabledBySetting) return;
		int w = getWidth();
		int h = getHeight();
		if (w <= 0 || h <= 0) return;

		float barSpacing = dp(2);
		int count = barCount;
		float totalSpacing = barSpacing * (count - 1);
		float barWidth = (w - totalSpacing) / (float) count;
		float maxBarHeight = h * 0.5f; // 上半区域，避免遮挡歌词
		float bottom = h * 0.95f; // 底对齐稍微上移

		for (int i = 0; i < count; i++) {
			float level = i < barLevels.length ? barLevels[i] : 0f;
			float bh = maxBarHeight * level;
			float left = i * (barWidth + barSpacing);
			float top = bottom - bh;
			canvas.drawRoundRect(left, top, left + barWidth, bottom, dp(1), dp(1), barPaint);
		}
	}

	private float dp(float v) { return v * (getResources().getDisplayMetrics().density); }
} 