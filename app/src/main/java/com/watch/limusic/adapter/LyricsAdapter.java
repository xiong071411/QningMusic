package com.watch.limusic.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;
import com.watch.limusic.util.LyricsParser;

import java.util.List;

public class LyricsAdapter extends RecyclerView.Adapter<LyricsAdapter.VH> {
	public interface OnLineClickListener { void onLineClick(int position, long startMs); }

	private final Context context;
	private final List<LyricsParser.Line> lines;
	private int currentIndex = -1;
	private OnLineClickListener listener;
	private int sizeCurrentSp;
	private int sizeOtherSp;
	private int minItemHeightPx;
	private int baseLineHeightPx;
	private int currentLineHeightPx;
	private final int basePadPx; // 单侧基础内边距
	private boolean smoothEnabled = false;
	private boolean longMarqueeEnabled = false; // 新增：允许长句滚动显示
	private final android.view.animation.Interpolator decel = new DecelerateInterpolator();
	private long scrollWindowMs = 0L; // 下一句剩余时间窗口

	public void setScrollWindowMs(long windowMs) { this.scrollWindowMs = Math.max(0L, windowMs); }

	public LyricsAdapter(Context context, List<LyricsParser.Line> lines) {
		this.context = context;
		this.lines = lines;
		setHasStableIds(true);
		SharedPreferences sp = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE);
		this.sizeCurrentSp = sp.getInt("lyric_size_current_sp", 24);
		this.sizeOtherSp = sp.getInt("lyric_size_other_sp", 16);
		this.smoothEnabled = sp.getBoolean("lyric_smooth_enabled", true);
		this.longMarqueeEnabled = sp.getBoolean("lyric_long_marquee_enabled", true);
		this.basePadPx = dp(2);
		recomputeHeights();
	}

	// 新增：提供预测当前行高度（用于一次性居中定位）
	public int getPredictedCurrentItemHeightPx() {
		int extra = Math.max(0, currentLineHeightPx - baseLineHeightPx);
		int half = (extra + 1) / 2;
		return Math.max(minItemHeightPx, currentLineHeightPx + basePadPx * 2); // 直接返回当前行的目标高度
	}

	private int dp(int v) { return (int) (context.getResources().getDisplayMetrics().density * v + 0.5f); }
	private float spToPx(float sp) { return sp * context.getResources().getDisplayMetrics().scaledDensity; }
	private static int lineHeightPxFor(Paint p) {
		Paint.FontMetricsInt fm = p.getFontMetricsInt();
		return Math.max(fm.bottom - fm.top, (int) Math.ceil(p.getFontSpacing()));
	}
	private void recomputeHeights() {
		Paint pOther = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
		pOther.setTextSize(spToPx(sizeOtherSp));
		baseLineHeightPx = lineHeightPxFor(pOther);
		Paint pCur = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
		pCur.setTextSize(spToPx(sizeCurrentSp));
		currentLineHeightPx = lineHeightPxFor(pCur);
		minItemHeightPx = baseLineHeightPx + basePadPx * 2; // 紧凑默认高度
	}

	public void setOnLineClickListener(OnLineClickListener l) { this.listener = l; }

	public void setCurrentIndex(int idx) {
		if (idx == this.currentIndex) return;
		int old = this.currentIndex;
		this.currentIndex = idx;
		if (old >= 0) notifyItemChanged(old, Boolean.FALSE);
		if (idx >= 0) notifyItemChanged(idx, Boolean.TRUE);
	}

	public void reloadSizes() {
		SharedPreferences sp = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE);
		this.sizeCurrentSp = sp.getInt("lyric_size_current_sp", 24);
		this.sizeOtherSp = sp.getInt("lyric_size_other_sp", 16);
		this.smoothEnabled = sp.getBoolean("lyric_smooth_enabled", true);
		this.longMarqueeEnabled = sp.getBoolean("lyric_long_marquee_enabled", true);
		recomputeHeights();
		notifyDataSetChanged();
	}

	@Override public long getItemId(int position) {
		LyricsParser.Line line = lines.get(position);
		long t = line.startMs;
		int h = line.text != null ? line.text.hashCode() : 0;
		return (t << 32) ^ (h & 0xffffffffL);
	}

	@NonNull @Override
	public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(context).inflate(R.layout.item_lyric_line, parent, false);
		VH vh = new VH(v);
		vh.text.getPaint().setAntiAlias(true);
		vh.text.getPaint().setSubpixelText(true);
		vh.text.setIncludeFontPadding(false);
		vh.text.setMinHeight(minItemHeightPx);
		vh.text.setPadding(vh.text.getPaddingLeft(), basePadPx, vh.text.getPaddingRight(), basePadPx);
		return vh;
	}

	@Override public void onBindViewHolder(@NonNull VH h, int position) { bindInternal(h, position, null); }
	@Override public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
		if (payloads != null && !payloads.isEmpty() && payloads.get(payloads.size()-1) instanceof Boolean) {
			bindInternal(h, position, (Boolean) payloads.get(payloads.size()-1));
			return;
		}
		bindInternal(h, position, null);
	}

	private void stopMarqueeIfAny(@NonNull VH h) {
		if (h.marquee != null) { h.marquee.cancel(); h.marquee = null; }
		h.text.scrollTo(0, 0);
		// 恢复两行省略外观
		h.text.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
		h.text.setSingleLine(false);
		h.text.setMaxLines(2);
		h.text.setEllipsize(TextUtils.TruncateAt.END);
		h.text.setSelected(false);
		h.text.setHorizontallyScrolling(false);
		// 关闭可能的硬件层
		try { h.text.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignore) {}
	}

	private void maybeStartHorizontalMarquee(@NonNull VH h) {
		if (!longMarqueeEnabled) return;
		// 先判断两行模式下是否发生省略
		h.text.post(() -> {
			android.text.Layout layout = h.text.getLayout();
			if (layout == null) return;
			int visibleLines = layout.getLineCount();
			boolean overflow = false;
			if (visibleLines >= 2) {
				int ellipsis2 = layout.getEllipsisCount(1);
				overflow = (ellipsis2 > 0) || (visibleLines > 2);
			}
			if (!overflow) return;
			int twoLineHeight = h.text.getLineHeight() * 2 + h.text.getPaddingTop() + h.text.getPaddingBottom();
			h.text.setHeight(twoLineHeight);
			// 在两行视窗内进行水平滚动：禁用换行，使用单行宽度进行横向滚动
			h.text.setSingleLine(true);
			h.text.setMaxLines(1);
			h.text.setEllipsize(null);
			h.text.setSelected(true);
			h.text.setHorizontallyScrolling(true);
			// 垂直居中以匹配两行视窗的视觉
			try { h.text.setGravity(android.view.Gravity.CENTER_VERTICAL); } catch (Exception ignore) {}
			// 自定义时长：计算文本宽度与可视宽度，估算速度，保证在下一句前滚完一遍
			long window = Math.max(500L, scrollWindowMs);
			int viewW = h.text.getWidth() - h.text.getPaddingLeft() - h.text.getPaddingRight();
			float textW = h.text.getPaint().measureText(h.text.getText().toString());
			float delta = Math.max(0f, textW - Math.max(0, viewW));
			if (delta > 0 && viewW > 0) {
				float duration = Math.max(800f, Math.min(15000f, window * 0.95f));
				if (h.marquee != null) { h.marquee.cancel(); }
				h.marquee = android.animation.ValueAnimator.ofInt(0, (int) delta);
				h.marquee.setDuration((long) duration);
				h.marquee.setInterpolator(new LinearInterpolator());
				h.marquee.addUpdateListener(a -> h.text.scrollTo((int) a.getAnimatedValue(), 0));
				// 降耗：横向滚动期间启用硬件层
				try { h.text.setLayerType(View.LAYER_TYPE_HARDWARE, null); } catch (Throwable ignore) {}
				h.marquee.addListener(new android.animation.AnimatorListenerAdapter() {
					@Override public void onAnimationEnd(android.animation.Animator animation) {
						try { h.text.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignore) {}
					}
				});
				h.marquee.start();
			} else {
				// 无需滚动
				h.text.setSelected(false);
				h.text.setHorizontallyScrolling(false);
				try { h.text.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignore) {}
			}
			// 不使用系统 repeat，避免不确定速度

		});
	}

	private void bindInternal(@NonNull VH h, int position, Boolean becomingCurrent) {
		LyricsParser.Line line = lines.get(position);
		h.text.setText(line.text);
		boolean isCurrent = position == currentIndex;
		// 每次绑定先停止可能存在的滚动，并按默认两行省略显示
		stopMarqueeIfAny(h);
		if (!smoothEnabled || becomingCurrent == null) {
			h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, isCurrent ? sizeCurrentSp : sizeOtherSp);
			h.text.setMinHeight(minItemHeightPx);
			// 确保复用时不保留缩放状态，左对齐枢轴
			h.text.setPivotX(0f);
			h.text.setScaleX(1f);
			h.text.setScaleY(1f);
			if (isCurrent) {
				int extra = Math.max(0, currentLineHeightPx - baseLineHeightPx);
				int half = (extra + 1) / 2;
				h.text.setPadding(h.text.getPaddingLeft(), basePadPx + half, h.text.getPaddingRight(), basePadPx + half);
				// 尝试在两行视窗内启用水平跑马灯
				maybeStartHorizontalMarquee(h);
			} else {
				h.text.setPadding(h.text.getPaddingLeft(), basePadPx, h.text.getPaddingRight(), basePadPx);
			}
		} else {
			// 平滑模式：不再动画 textSize，改为整体缩放，避免长句出现“从左到右依次放大”的违和感
			h.text.animate().cancel();
			if (Boolean.TRUE.equals(becomingCurrent)) {
				// 成为当前：立即设置目标字号与内边距，再做细微缩放过渡 0.96 -> 1.0（以左侧为枢轴，避免左边缘“外凸/回弹”）
				h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeCurrentSp);
				int extra = Math.max(0, currentLineHeightPx - baseLineHeightPx);
				int half = (extra + 1) / 2;
				h.text.setPadding(h.text.getPaddingLeft(), basePadPx + half, h.text.getPaddingRight(), basePadPx + half);
				h.text.setPivotX(0f);
				h.text.setScaleX(0.96f);
				h.text.setScaleY(0.96f);
				// 降耗：缩放动画期间启用硬件层
				try { h.text.setLayerType(View.LAYER_TYPE_HARDWARE, null); } catch (Throwable ignore) {}
				h.text.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(decel)
					.withEndAction(() -> { try { h.text.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignore) {} })
					.start();
				// 动画结束后再尝试水平跑马灯（下一帧），避免干扰观感
				h.text.postDelayed(() -> maybeStartHorizontalMarquee(h), 16);
			} else {
				// 退出当前：立即恢复字号与内边距，做 1.0 -> 0.96 的轻缩放（以左侧为枢轴）
				h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeOtherSp);
				h.text.setPadding(h.text.getPaddingLeft(), basePadPx, h.text.getPaddingRight(), basePadPx);
				h.text.setPivotX(0f);
				h.text.setScaleX(1f);
				h.text.setScaleY(1f);
				try { h.text.setLayerType(View.LAYER_TYPE_HARDWARE, null); } catch (Throwable ignore) {}
				h.text.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).setInterpolator(decel)
					.withEndAction(() -> { try { h.text.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignore) {} })
					.start();
			}
		}
		h.itemView.setOnClickListener(v -> { if (listener != null) listener.onLineClick(position, line.startMs); });
	}

	@Override public int getItemCount() { return lines != null ? lines.size() : 0; }

	public static class VH extends RecyclerView.ViewHolder {
		TextView text;
		android.animation.ValueAnimator marquee;
		public VH(@NonNull View itemView) {
			super(itemView);
			text = itemView.findViewById(R.id.text);
		}
	}
} 