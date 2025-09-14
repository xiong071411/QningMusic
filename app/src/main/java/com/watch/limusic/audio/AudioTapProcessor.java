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

	private int barCount = DEFAULT_BARS;
	private float[] bars = new float[barCount];
	private float ema = 0.35f; // 平滑系数
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

	public void setPlaying(boolean p) { this.playing = p; }
	public void setBarCount(int c) {
		int n = Math.max(8, Math.min(64, c));
		if (n != barCount) { barCount = n; bars = new float[barCount]; updateBands(); }
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
		float fMin = 60f;
		float fMax = Math.min(10000f, nyq - 1000f); // 上限略降以降噪且降耗
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
		// 4) 频带 Goertzel
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
			float level = (float)((db - MIN_DB) / (-MIN_DB));
			// 轻微增强高频响应（右侧），最多约+8%
			float hfBoost = 1.0f + 0.08f * (b / (float)Math.max(1, barCount - 1));
			level *= hfBoost;
			if (level < 0f) level = 0f; else if (level > 1f) level = 1f;
			bars[b] = (float)(bars[b] + (level - bars[b]) * ema);
		}
		com.watch.limusic.audio.AudioLevelBus.publish(bars, playing);
	}

	@Override public void queueEndOfStream() { inputEnded = true; }
	@Override public ByteBuffer getOutput() { return outputBuffer; }
	@Override public boolean isEnded() { return inputEnded && outputBuffer == EMPTY_BUFFER; }
	@Override public void flush() { outputBuffer = EMPTY_BUFFER; inputEnded = false; monoFill = 0; }
	@Override public void reset() { flush(); inputFormat = AudioFormat.NOT_SET; outputFormat = AudioFormat.NOT_SET; bars = new float[barCount]; updateBands(); }
} 