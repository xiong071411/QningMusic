package com.watch.limusic.audio;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public final class AudioTapProcessor implements AudioProcessor {
	private static final int DEFAULT_BARS = 16; // 降耗：降低默认频带数
	private static final int FRAME_SIZE = 512;  // 降耗：缩短分析帧长
	private static final double MIN_DB = -60.0; // dBFS 映射下限
	// 新增：频段范围与动态响应参数（手表优先）
	private static final float F_MIN_HZ = 50f;      // 缩窄低端，去除次低频轰鸣
	private static final float F_MAX_HZ = 6000f;    // 移除超高频，避免右侧“死条”
	private static final int ATTACK_MS = 20;        // 快上升，便于捕捉鼓点
	private static final int RELEASE_MS = 220;      // 慢下降，视觉平滑
	private static final int PEAK_HOLD_MS = 70;     // 缩短峰值保持
	private static final int HOLD_RELEASE_MS = 70;  // 持峰结束的缓释时间常数
	private static final float LOW_SHELF_DB = -6f;  // <80Hz 更强抑制
	private static final float KICK_BOOST_DB = 2f;  // 80–160Hz 鼓点轻微加权
	// 新增：噪声门限滞回（开门/关门）
	private static final float GATE_OPEN_DB = -58f;
	private static final float GATE_CLOSE_DB = -62f;

	private int barCount = DEFAULT_BARS;
	private float[] bars = new float[barCount];
	private float ema = 0.35f; // 平滑系数（保留作为兜底）
	private int sampleRateHz = 48000;
	private int channels = 2;
	private boolean playing = false;

	private AudioFormat inputFormat = AudioFormat.NOT_SET;
	private AudioFormat outputFormat = AudioFormat.NOT_SET;
	private ByteBuffer outputBuffer = EMPTY_BUFFER;
	private boolean inputEnded = false;

	private float[] bandCentersHz = null;
	private double[] coeff = null;
	private final float[] mono = new float[FRAME_SIZE];
	private int monoFill = 0;
	// 发布节流
	private long lastPublishMs = 0L;
	// 复用：Hann窗与加窗后的帧缓冲，避免在频带循环内重复计算
	private double[] hannWin = null;
	private final float[] winBuf = new float[FRAME_SIZE];
	// 新增：双时间常数与峰值保持的状态
	private float[] smoothed = new float[barCount];
	private float[] held = new float[barCount];
	private long[] holdUntilMs = new long[barCount];
	// 新增：噪声门限滞回状态
	private boolean[] gateOpen = new boolean[barCount];

	public void setPlaying(boolean p) { this.playing = p; }
	public void setBarCount(int c) {
		int n = Math.max(8, Math.min(64, c));
		if (n != barCount) { barCount = n; bars = new float[barCount]; smoothed = new float[barCount]; held = new float[barCount]; holdUntilMs = new long[barCount]; gateOpen = new boolean[barCount]; updateBands(); }
	}
	public void setEma(float e) { this.ema = Math.max(0.05f, Math.min(0.9f, e)); }

	@Override public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
		inputFormat = inputAudioFormat;
		outputFormat = inputAudioFormat;
		if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
			sampleRateHz = inputAudioFormat.sampleRate;
			channels = Math.max(1, inputAudioFormat.channelCount);
			updateBands();
		}
		return outputFormat;
	}

	@Override public boolean isActive() { return true; }

	@Override public void queueInput(ByteBuffer inputBuffer) {
		if (inputBuffer == null) return;
		int remaining = inputBuffer.remaining();
		if (remaining == 0) { outputBuffer = EMPTY_BUFFER; return; }
		// 分析但保持透传
		if (inputFormat.encoding == C.ENCODING_PCM_16BIT) {
			ShortBuffer sb = inputBuffer.asShortBuffer();
			int total = sb.remaining();
			int step = Math.max(1, channels); // 低成本下混：取一个通道
			for (int i = 0; i < total; i += step) {
				float v = sb.get(i) / 32768f;
				mono[monoFill++] = v;
				if (monoFill >= FRAME_SIZE) {
					analyzeFrame(mono, FRAME_SIZE);
					monoFill = 0;
				}
			}
		}
		if (outputBuffer.capacity() < remaining) {
			outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
		}
		outputBuffer.clear();
		outputBuffer.put(inputBuffer.asReadOnlyBuffer());
		outputBuffer.flip();
		inputBuffer.position(inputBuffer.limit());
	}

	private void updateBands() {
		if (barCount <= 0 || sampleRateHz <= 0) return;
		bandCentersHz = new float[barCount];
		coeff = new double[barCount];
		float nyq = sampleRateHz * 0.5f;
		float fMin = F_MIN_HZ;
		float fMax = Math.min(F_MAX_HZ, nyq * 0.98f); // 上限略降以降噪且降耗
		if (fMax <= fMin + 200f) fMax = nyq * 0.9f;
		double ratio = Math.pow(fMax / fMin, 1.0 / Math.max(1, barCount - 1));
		for (int i = 0; i < barCount; i++) {
			float fc = (float)(fMin * Math.pow(ratio, i));
			bandCentersHz[i] = Math.min(fc, nyq * 0.98f);
			double w = 2.0 * Math.PI * (bandCentersHz[i] / sampleRateHz);
			coeff[i] = 2.0 * Math.cos(w);
		}
		// 窗函数缓存无效化（如帧长/采样率变化）
		hannWin = null;
		// 状态清零
		for (int i = 0; i < barCount; i++) { bars[i] = 0f; smoothed[i] = 0f; held[i] = 0f; holdUntilMs[i] = 0L; gateOpen[i] = false; }
	}

	private float alphaFromMs(long dtMs, float tauMs) {
		if (dtMs <= 0) return 1f;
		float a = (float) (1.0 - Math.exp(-dtMs / Math.max(1.0, tauMs)));
		return a < 0f ? 0f : (a > 1f ? 1f : a);
	}

	private void analyzeFrame(float[] frame, int n) {
		if (bandCentersHz == null || coeff == null) return;
		// 发布节流，按UI设定FPS控制分析发布频率
		int fps = Math.max(5, Math.min(60, com.watch.limusic.audio.AudioLevelBus.getMaxFps()));
		long now = System.currentTimeMillis();
		long minInterval = 1000L / Math.max(1, fps);
		if (now - lastPublishMs < minInterval) return;
		lastPublishMs = now;

		// 1) 去直流
		double mean = 0.0;
		for (int i = 0; i < n; i++) mean += frame[i];
		mean /= Math.max(1, n);
		// 2) 预计算/复用 Hann 窗
		if (hannWin == null || hannWin.length != n) {
			hannWin = new double[n];
			for (int i = 0; i < n; i++) {
				hannWin[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (n - 1.0)));
			}
		}
		// 3) 生成加窗后的帧缓冲，供所有频带复用
		for (int i = 0; i < n; i++) {
			double x = (frame[i] - mean) * hannWin[i];
			winBuf[i] = (float) x;
		}
		// 4) 频带 Goertzel + 能量整形 + 双时间常数 + 峰值保持(缓释) + 门限滞回
		float aUp = alphaFromMs(minInterval, ATTACK_MS);
		float aDn = alphaFromMs(minInterval, RELEASE_MS);
		float aHold = alphaFromMs(minInterval, HOLD_RELEASE_MS);
		for (int b = 0; b < barCount; b++) {
			double c = coeff[b];
			double s0, s1 = 0.0, s2 = 0.0;
			for (int i = 0; i < n; i++) {
				double x = winBuf[i];
				s0 = x + c * s1 - s2;
				s2 = s1;
				s1 = s0;
			}
			double power = s1 * s1 + s2 * s2 - c * s1 * s2;
			double mag = Math.sqrt(Math.max(0.0, power)) / (n * 0.5);
			double db = 20.0 * Math.log10(mag + 1e-9);
			if (db > 0) db = 0;

			// 门限滞回：决定是否开门
			boolean open = gateOpen[b];
			if (open) {
				if (db <= GATE_CLOSE_DB) { open = false; gateOpen[b] = false; }
			} else {
				if (db >= GATE_OPEN_DB) { open = true; gateOpen[b] = true; }
			}

			float targetLevel;
			if (!open || db <= MIN_DB) {
				// 关门或深低于噪声下限：拉向0
				targetLevel = 0f;
			} else {
				// 频段加权（在dB域操作更符合感知）
				float fc = bandCentersHz[b];
				float adjustDb = 0f;
				if (fc < 70f) adjustDb += LOW_SHELF_DB;           // 低频强抑制
				else if (fc < 90f) adjustDb += (LOW_SHELF_DB * 0.5f); // 70–90Hz 半量抑制
				else if (fc <= 160f) adjustDb += KICK_BOOST_DB;   // 鼓点加权
				db = Math.max((float)MIN_DB, Math.min(0.0f, (float)(db + adjustDb)));
				targetLevel = (float)((db - MIN_DB) / (-MIN_DB)); // 归一化0..1
				// 轻微增强高频响应（右侧），最多约+8%
				float hfBoost = 1.0f + 0.08f * (b / (float)Math.max(1, barCount - 1));
				targetLevel *= hfBoost;
				if (targetLevel < 0f) targetLevel = 0f; else if (targetLevel > 1f) targetLevel = 1f;
			}
			// 双时间常数平滑
			float prev = smoothed[b];
			float a = targetLevel > prev ? aUp : aDn;
			float sm = prev + (targetLevel - prev) * a;
			smoothed[b] = sm;
			bars[b] = applyHoldAndGet(now, b, sm, aHold);
		}
		com.watch.limusic.audio.AudioLevelBus.publish(bars, playing);
	}

	private float applyHoldAndGet(long now, int idx, float value, float releaseAlpha) {
		float h = held[idx];
		if (value > h) {
			held[idx] = value;
			holdUntilMs[idx] = now + PEAK_HOLD_MS;
			h = value;
		} else {
			if (now < holdUntilMs[idx]) {
				// 仍在保持期间，维持峰值
			} else {
				// 持峰结束：缓释到当前值，避免瞬间跌落
				held[idx] = h + (value - h) * releaseAlpha;
				h = held[idx];
			}
		}
		return h > value ? h : value;
	}

	@Override public void queueEndOfStream() { inputEnded = true; }
	@Override public ByteBuffer getOutput() { ByteBuffer out = outputBuffer; outputBuffer = EMPTY_BUFFER; return out; }
	@Override public boolean isEnded() { return inputEnded && outputBuffer == EMPTY_BUFFER; }
	@Override public void flush() { outputBuffer = EMPTY_BUFFER; inputEnded = false; monoFill = 0; }
	@Override public void reset() { flush(); inputFormat = AudioFormat.NOT_SET; outputFormat = AudioFormat.NOT_SET; bars = new float[barCount]; smoothed = new float[barCount]; held = new float[barCount]; holdUntilMs = new long[barCount]; gateOpen = new boolean[barCount]; updateBands(); }
} 