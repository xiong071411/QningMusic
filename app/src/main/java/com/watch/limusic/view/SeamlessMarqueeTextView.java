package com.watch.limusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Choreographer;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * 首尾相接的无缝水平滚动文本，用于替代标准跑马灯的“回弹”跳变。
 * 仅当文本宽度大于可视宽度且可见时才启动渲染循环，适合手表设备低功耗场景。
 */
public class SeamlessMarqueeTextView extends AppCompatTextView implements Choreographer.FrameCallback {
	private float scrollOffsetPx = 0f;
	private float textWidthPx = 0f;
	private float gapPx;
	private float speedPxPerSec;
	private boolean ticking = false;
	private long lastFrameNs = 0L;

	public SeamlessMarqueeTextView(Context context) {
		super(context);
		init(context);
	}

	public SeamlessMarqueeTextView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	public SeamlessMarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init(context);
	}

	private void init(Context ctx) {
		setEllipsize(null); // 自己绘制不需要系统马灯
		setSingleLine(true);
		setHorizontallyScrolling(true);
		setMarqueeRepeatLimit(-1);
		// 默认间距与速度（可按需微调）
		gapPx = dp(16);
		speedPxPerSec = dp(36); // 稍快但不过度
		setSelected(true); // 与现有代码一致，不影响自绘
		setFocusable(true);
		setFocusableInTouchMode(true);
	}

	private float dp(float v) {
		return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
	}

	public void setGapDp(float gapDp) { this.gapPx = dp(gapDp); invalidateAndMaybeTick(); }
	public void setSpeedDpPerSec(float speedDpPerSec) { this.speedPxPerSec = dp(speedDpPerSec); }

	@Override
	protected void onTextChanged(CharSequence text, int start, int before, int after) {
		super.onTextChanged(text, start, before, after);
		recomputeTextWidth();
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		invalidateAndMaybeTick();
	}

	private void recomputeTextWidth() {
		CharSequence t = getText();
		if (TextUtils.isEmpty(t)) { textWidthPx = 0f; return; }
		textWidthPx = getPaint().measureText(t.toString());
		invalidateAndMaybeTick();
	}

	private void invalidateAndMaybeTick() {
		invalidate();
		boolean needScroll = textWidthPx > getWidth() && getWidth() > 0 && getVisibility() == VISIBLE;
		if (needScroll) startTicking(); else stopTicking();
	}

	private void startTicking() {
		if (ticking) return;
		ticking = true;
		lastFrameNs = 0L;
		Choreographer.getInstance().postFrameCallback(this);
	}

	private void stopTicking() {
		if (!ticking) return;
		ticking = false;
		Choreographer.getInstance().removeFrameCallback(this);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		invalidateAndMaybeTick();
	}

	@Override
	protected void onDetachedFromWindow() {
		stopTicking();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onVisibilityChanged(android.view.View changedView, int visibility) {
		super.onVisibilityChanged(changedView, visibility);
		invalidateAndMaybeTick();
	}

	@Override
	public void doFrame(long frameTimeNanos) {
		if (!ticking) return;
		float loopWidth = textWidthPx + gapPx;
		if (loopWidth <= 0f || getWidth() <= 0) { stopTicking(); return; }
		if (lastFrameNs == 0L) lastFrameNs = frameTimeNanos;
		float dtSec = (frameTimeNanos - lastFrameNs) / 1_000_000_000f;
		lastFrameNs = frameTimeNanos;
		scrollOffsetPx += speedPxPerSec * dtSec;
		if (scrollOffsetPx >= loopWidth) {
			scrollOffsetPx -= loopWidth; // 模循环，视觉无跳变
		}
		invalidate();
		Choreographer.getInstance().postFrameCallback(this);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		// 若不需要滚动则走默认绘制
		if (textWidthPx <= getWidth() || getWidth() <= 0) {
			super.onDraw(canvas);
			return;
		}
		CharSequence text = getText();
		if (TextUtils.isEmpty(text)) { return; }
		Paint p = getPaint();
		p.setAntiAlias(true);
		float loopWidth = textWidthPx + gapPx;
		float startX = -scrollOffsetPx;
		// 计算基线，保持与 TextView 默认垂直对齐一致
		Paint.FontMetrics fm = p.getFontMetrics();
		float baseline = (getHeight() - (fm.bottom - fm.top)) / 2f - fm.top;
		// 第一段
		canvas.drawText(text.toString(), startX, baseline, p);
		// 第二段（紧接其后）
		canvas.drawText(text.toString(), startX + loopWidth, baseline, p);
	}
} 