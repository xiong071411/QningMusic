package com.watch.limusic.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.graphics.drawable.ColorDrawable;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;
import com.watch.limusic.model.ArtistItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ArtistAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	public interface OnArtistClickListener { void onArtistClick(ArtistItem artist, int position); }

	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ITEM = 1;

	private final Context context;
	private final ArrayList<ArtistItem> pinned = new ArrayList<>();
	private final ArrayList<ArtistItem> unpinned = new ArrayList<>();
	private final Map<String, Integer> letterFirstIndex = new HashMap<>(); // 仅针对未顶置
	private OnArtistClickListener listener;
	private final SharedPreferences sp;
	private final LinkedHashSet<String> pinnedKeys = new LinkedHashSet<>(); // 归一化name

	public ArtistAdapter(Context context) {
		this.context = context;
		this.sp = context.getSharedPreferences("artist_prefs", Context.MODE_PRIVATE);
		setHasStableIds(true);
		loadPinnedFromPrefs();
	}

	public void setOnArtistClickListener(OnArtistClickListener l) { this.listener = l; }

	public void setData(List<ArtistItem> data) {
		pinned.clear();
		unpinned.clear();
		if (data != null) {
			for (ArtistItem it : data) {
				String key = normalize(it.getName());
				if (pinnedKeys.contains(key)) pinned.add(it); else unpinned.add(it);
			}
		}
		rebuildLetterIndex();
		notifyDataSetChanged();
	}

	private void rebuildLetterIndex() {
		letterFirstIndex.clear();
		int base = getPinnedSectionCount() > 0 ? (1 + pinned.size() + 1) : 0; // 已顶置头+内容 + 全部艺术家头
		for (int i = 0; i < unpinned.size(); i++) {
			String letter = simplifyLetter(unpinned.get(i).getSortLetter());
			if (!letterFirstIndex.containsKey(letter)) {
				letterFirstIndex.put(letter, base + i);
			}
		}
	}

	private static String simplifyLetter(String s) {
		if (s == null || s.isEmpty()) return "#";
		char c = s.charAt(0);
		if (c >= '0' && c <= '9') return "#";
		if (c >= 'A' && c <= 'Z') return String.valueOf(c);
		if (c >= 'a' && c <= 'z') return String.valueOf(Character.toUpperCase(c));
		return "#";
	}

	public List<String> getAvailableIndexLetters() {
		Set<String> set = new HashSet<>();
		for (String k : letterFirstIndex.keySet()) set.add(simplifyLetter(k));
		ArrayList<String> list = new ArrayList<>();
		if (set.contains("#")) list.add("#");
		for (char c = 'A'; c <= 'Z'; c++) { String k = String.valueOf(c); if (set.contains(k)) list.add(k); }
		return list;
	}

	public int getPositionForLetter(String letter) {
		Integer pos = letterFirstIndex.get(letter);
		if (pos == null && "#".equals(letter)) pos = letterFirstIndex.get("#");
		return pos != null ? pos : -1;
	}

	@Override public long getItemId(int position) {
		Object it = getItemAt(position);
		if (it instanceof ArtistItem) {
			ArtistItem a = (ArtistItem) it;
			return a.getStableId();
		}
		// 头部使用固定负ID避免与内容冲突
		return 0x7FFFFFFFL - position;
	}

	@Override public int getItemCount() {
		int count = 0;
		if (getPinnedSectionCount() > 0) {
			count += 1; // 已顶置头
			count += pinned.size();
			count += 1; // 全部艺术家头
		}
		count += unpinned.size();
		return count;
	}

	@Override public int getItemViewType(int position) {
		if (getPinnedSectionCount() == 0) return TYPE_ITEM;
		if (position == 0) return TYPE_HEADER; // 已顶置
		int afterPinnedHeader = position - 1;
		if (afterPinnedHeader < pinned.size()) return TYPE_ITEM;
		if (afterPinnedHeader == pinned.size()) return TYPE_HEADER; // 全部艺术家
		return TYPE_ITEM;
	}

	@NonNull @Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		if (viewType == TYPE_HEADER) {
			View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_artist_section_header, parent, false);
			return new HeaderHolder(v);
		}
		View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_artist, parent, false);
		return new ItemHolder(v);
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
		if (holder instanceof HeaderHolder) {
			HeaderHolder hh = (HeaderHolder) holder;
			if (position == 0) {
				hh.title.setText(R.string.section_pinned);
			} else {
				hh.title.setText(R.string.section_all_artists);
			}
			return;
		}
		ItemHolder ih = (ItemHolder) holder;
		ArtistItem it = (ArtistItem) getItemAt(position);
		ih.name.setText(it.getName());
		ih.count.setText(String.format(Locale.getDefault(), "%d 首歌曲", it.getSongCount()));
		ih.itemView.setOnClickListener(v -> { if (listener != null) listener.onArtistClick(it, ih.getBindingAdapterPosition()); });
		ih.itemView.setOnLongClickListener(v -> { showPinPopup(v, it); return true; });
	}

	private Object getItemAt(int position) {
		if (getPinnedSectionCount() == 0) {
			return unpinned.get(position);
		}
		if (position == 0) return "header_pinned";
		int afterPinnedHeader = position - 1;
		if (afterPinnedHeader < pinned.size()) return pinned.get(afterPinnedHeader);
		if (afterPinnedHeader == pinned.size()) return "header_all";
		return unpinned.get(afterPinnedHeader - pinned.size() - 1);
	}

	private int getPinnedSectionCount() { return pinned.isEmpty() ? 0 : 1; }

	private void showPinPopup(View anchor, ArtistItem it) {
		View content = LayoutInflater.from(context).inflate(R.layout.popup_pin_artist, null, false);
		TextView btn = content.findViewById(R.id.btn_pin);
		btn.setText(pinnedKeys.contains(normalize(it.getName())) ? R.string.unpin_artist : R.string.pin_artist);
		final PopupWindow popup = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
		popup.setBackgroundDrawable(new ColorDrawable(0xCC2D2D2D));
		popup.setOutsideTouchable(true);
		int[] loc = new int[2];
		anchor.getLocationOnScreen(loc);
		// 优先显示在项的右侧
		anchor.post(() -> {
			try {
				popup.showAsDropDown(anchor, anchor.getWidth()/2, -anchor.getHeight()/2, Gravity.END);
			} catch (Exception e) {
				popup.showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0] + anchor.getWidth(), loc[1]);
			}
		});
		btn.setOnClickListener(v -> { togglePin(it); popup.dismiss(); });
	}

	private void togglePin(ArtistItem it) {
		String key = normalize(it.getName());
		if (pinnedKeys.contains(key)) pinnedKeys.remove(key); else pinnedKeys.add(key);
		sp.edit().putStringSet("pinned_keys", new HashSet<>(pinnedKeys)).apply();
		// 重新分区
		if (unpinned.removeIf(a -> normalize(a.getName()).equals(key))) pinned.add(it); else if (pinned.removeIf(a -> normalize(a.getName()).equals(key))) unpinned.add(it);
		rebuildLetterIndex();
		notifyDataSetChanged();
	}

	private void loadPinnedFromPrefs() {
		try {
			Set<String> set = sp.getStringSet("pinned_keys", null);
			if (set != null) pinnedKeys.addAll(set);
		} catch (Exception ignore) {}
	}

	static class HeaderHolder extends RecyclerView.ViewHolder {
		final TextView title;
		HeaderHolder(View itemView) { super(itemView); title = (TextView) itemView; }
	}
	static class ItemHolder extends RecyclerView.ViewHolder {
		final TextView name; final TextView count;
		ItemHolder(View itemView) { super(itemView); name = itemView.findViewById(R.id.artist_name); count = itemView.findViewById(R.id.artist_count); }
	}

	private static String normalize(String name) {
		return name != null ? name.trim().toLowerCase() : "(unknown)";
	}
} 