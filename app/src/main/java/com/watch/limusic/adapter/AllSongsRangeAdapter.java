package com.watch.limusic.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;
import com.watch.limusic.database.MusicRepository;
import com.watch.limusic.download.DownloadManager;
import com.watch.limusic.download.LocalFileDetector;
import com.watch.limusic.model.DownloadInfo;
import com.watch.limusic.model.Song;
import com.watch.limusic.model.SongWithIndex;
import com.watch.limusic.util.NetworkUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import android.util.SparseArray;
import android.graphics.Typeface;

// 新增：后台执行器与下载/缓存状态内存集合
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ConcurrentSkipListSet;

public class AllSongsRangeAdapter extends RecyclerView.Adapter<AllSongsRangeAdapter.ViewHolder> {
	private final Context context;
	private final MusicRepository musicRepository;
	private final LocalFileDetector localFileDetector;
	private final DownloadManager downloadManager;
	private SongAdapter.OnSongClickListener songClickListener;
	private SongAdapter.OnDownloadClickListener downloadClickListener;

	private int totalCount = 0;
	private int pageSize = 60;
	private boolean showCoverArt = false; // 所有歌曲不显示封面
	private boolean showDownloadStatus = true;
	private final SparseArray<SongWithIndex> loaded = new SparseArray<>();
	private final Set<Integer> loadingOffsets = new HashSet<>();
	private Map<String, Integer> letterOffsetMap = new HashMap<>();

	// 新增：后台单线程执行器，降低主线程干扰
	private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
		@Override public Thread newThread(Runnable r) {
			return new Thread(() -> {
				try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignore) {}
				r.run();
			}, "AllSongsRangeAdapter-IO");
		}
	});
	// 新增：内存集合，避免逐条文件系统查询
	private final Set<String> downloadedSongIds = new ConcurrentSkipListSet<>();

	// 选择模式支持
	private boolean selectionMode = false;
	private final java.util.LinkedHashSet<String> selectedIds = new java.util.LinkedHashSet<>();
	public interface OnItemLongClickListener { void onItemLongClick(int position); }
	private OnItemLongClickListener longClickListener;

	// 正在播放状态
	private String currentPlayingSongId = null;
	private boolean isCurrentlyPlaying = false;
	private int lastPlayingPosition = RecyclerView.NO_POSITION;

	public AllSongsRangeAdapter(Context context, MusicRepository repository, SongAdapter.OnSongClickListener listener) {
		this.context = context;
		this.musicRepository = repository;
		this.localFileDetector = new LocalFileDetector(context);
		this.downloadManager = DownloadManager.getInstance(context);
		this.songClickListener = listener;
		// 启用稳定ID，减少全量刷新和回收导致的闪烁/跳位
		setHasStableIds(true);
		// 异步预扫描下载目录，填充内存集合
		bgExecutor.execute(() -> {
			try {
				java.util.List<String> ids = localFileDetector.getAllDownloadedSongIds();
				if (ids != null) downloadedSongIds.addAll(ids);
			} catch (Throwable ignore) {}
		});
	}

	public void setOnDownloadClickListener(SongAdapter.OnDownloadClickListener listener) {
		this.downloadClickListener = listener;
	}

	public void setOnItemLongClickListener(OnItemLongClickListener l) { this.longClickListener = l; }
	public void setSelectionMode(boolean enable) { this.selectionMode = enable; if (!enable) selectedIds.clear(); notifyDataSetChanged(); }
	public boolean isSelectionMode() { return selectionMode; }
	public interface OnSelectionChanged { void onSelectionCountChanged(int count); }
	private OnSelectionChanged selectionChangedListener;
	public void setOnSelectionChangedListener(OnSelectionChanged l) { this.selectionChangedListener = l; }
			public void toggleSelect(String songId) { if (!selectionMode || songId == null) return; if (selectedIds.contains(songId)) selectedIds.remove(songId); else selectedIds.add(songId); if (selectionChangedListener != null) selectionChangedListener.onSelectionCountChanged(selectedIds.size()); notifyDataSetChanged(); }
	public java.util.List<String> getSelectedIdsInOrder() { return new java.util.ArrayList<>(selectedIds); }
	public String getSongIdAt(int position) { SongWithIndex swi = loaded.get(position); return swi != null ? swi.getId() : null; }

	public void setShowCoverArt(boolean show) { this.showCoverArt = show; notifyDataSetChanged(); }
	public void setShowDownloadStatus(boolean show) { this.showDownloadStatus = show; notifyDataSetChanged(); }

	public void setCurrentPlaying(String songId, boolean isPlaying) {
		if ((currentPlayingSongId == null && songId == null) || (currentPlayingSongId != null && currentPlayingSongId.equals(songId) && isCurrentlyPlaying == isPlaying)) return;
		int oldPos = getPositionBySongId(currentPlayingSongId);
		currentPlayingSongId = songId;
		isCurrentlyPlaying = isPlaying;
		int newPos = getPositionBySongId(songId);
		if (oldPos >= 0) notifyItemChanged(oldPos);
		if (newPos >= 0) notifyItemChanged(newPos);
		lastPlayingPosition = newPos;
	}

	public int getPositionBySongId(String songId) {
		if (songId == null) return -1;
		for (int i = 0; i < loaded.size(); i++) {
			int key = loaded.keyAt(i);
			SongWithIndex item = loaded.valueAt(i);
			if (item != null && songId.equals(item.getId())) return key;
		}
		return -1;
	}

	public void setTotalCount(int total) {
		this.totalCount = Math.max(0, total);
		notifyDataSetChanged();
	}

	// 新增：仅根据新总数做局部差量更新，避免全量刷新
	public void applyTotalCountAndDiff(int newTotal) {
		int old = this.totalCount;
		newTotal = Math.max(0, newTotal);
		if (newTotal == old) return;
		this.totalCount = newTotal;
		if (newTotal > old) {
			int inserted = newTotal - old;
			notifyItemRangeInserted(old, inserted);
		} else {
			int removed = old - newTotal;
			notifyItemRangeRemoved(newTotal, removed);
			// 同步清理已加载缓存中超出范围的条目
			for (int i = loaded.size() - 1; i >= 0; i--) {
				int key = loaded.keyAt(i);
				if (key >= newTotal) loaded.removeAt(i);
			}
		}
	}

	// 新增：对外暴露当前总数，便于比较
	public int getTotalCount() { return totalCount; }

	public void setLetterOffsetMap(Map<String,Integer> map) {
		this.letterOffsetMap = map != null ? map : new HashMap<>();
	}

	public void setPageSize(int size) { this.pageSize = Math.max(20, size); }

	public List<String> getAvailableIndexLetters() {
		List<String> letters = new ArrayList<>();
		if (letterOffsetMap.containsKey("#")) letters.add("#");
		for (char c = 'A'; c <= 'Z'; c++) {
			String k = String.valueOf(c);
			if (letterOffsetMap.containsKey(k)) letters.add(k);
		}
		return letters;
	}

	public int getPositionForLetter(String letter) {
		Integer pos = letterOffsetMap.get(letter);
		return pos != null ? Math.min(Math.max(0, pos), Math.max(0, totalCount - 1)) : -1;
	}

	@Override
	public int getItemCount() { return totalCount; }

	@Override
	public long getItemId(int position) {
		SongWithIndex swi = loaded.get(position);
		if (swi != null && swi.getId() != null) {
			// 用歌曲ID生成稳定long，减少碰撞
			return (long) swi.getId().hashCode() & 0xffffffffL;
		}
		// 占位行使用位置派生的稳定ID，待真实数据到达时自然过渡
		return 0x40000000L + position;
	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false);
		return new ViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		SongWithIndex item = loaded.get(position);
		if (item == null) {
			bindPlaceholder(holder, position);
			triggerLoadAround(position);
			return;
		}
		bindActual(holder, item, position);
	}

	private void bindPlaceholder(@NonNull ViewHolder holder, int position) {
		// 占位渲染：仅显示编号，其余置灰；占位行不可选
		holder.songTitle.setText(" ");
		holder.songArtist.setText(" ");
		holder.songDuration.setText(" ");
		holder.songNumber.setText(String.format(Locale.getDefault(), "%02d", position + 1));
		holder.checkbox.setVisibility(View.GONE);
		holder.songNumber.setVisibility(View.VISIBLE);
		holder.itemView.setBackgroundResource(R.drawable.item_background);
		holder.itemView.setAlpha(0.6f);
		holder.downloadContainer.setVisibility(View.GONE);
		holder.playingBar.setVisibility(View.GONE);
		holder.playingIcon.setVisibility(View.GONE);
	}

	private void bindActual(@NonNull ViewHolder holder, SongWithIndex songItem, int adapterPosition) {
		Song song = songItem.getSong();
		holder.itemView.setAlpha(1.0f);
		holder.songTitle.setText(song.getTitle());
		holder.songArtist.setText(song.getArtist());
		holder.songNumber.setText(songItem.getDisplayNumber());
		int duration = song.getDuration();
		int minutes = duration / 60;
		int seconds = duration % 60;
		holder.songDuration.setText(String.format(Locale.getDefault(), "%d:%02d", minutes, seconds));

		if (selectionMode) {
			holder.checkbox.setVisibility(View.VISIBLE);
			holder.songNumber.setVisibility(View.GONE);
			boolean checked = selectedIds.contains(song.getId());
			holder.checkbox.setImageResource(checked ? R.drawable.ic_check_box : R.drawable.ic_check_box_outline);
			holder.itemView.setBackgroundResource(checked ? R.drawable.item_song_selected_bg : R.drawable.item_background);
		} else {
			holder.checkbox.setVisibility(View.GONE);
			holder.songNumber.setVisibility(View.VISIBLE);
			holder.itemView.setBackgroundResource(R.drawable.item_background);
		}

		setupDownloadUI(holder, song);

		boolean isOfflineMode = !NetworkUtils.isNetworkAvailable(context);
		boolean isDownloaded = songItem.isCached();
		if (isOfflineMode) {
			int textColor = isDownloaded
				? context.getResources().getColor(R.color.textPrimary)
				: context.getResources().getColor(R.color.textDisabled);
			holder.songTitle.setTextColor(textColor);
			holder.songArtist.setTextColor(textColor);
			holder.songNumber.setTextColor(textColor);
			holder.songDuration.setTextColor(textColor);
			holder.itemView.setAlpha(isDownloaded ? 1.0f : 0.7f);
		} else {
			int defaultTextColor = context.getResources().getColor(R.color.textPrimary);
			holder.songTitle.setTextColor(defaultTextColor);
			holder.songArtist.setTextColor(defaultTextColor);
			holder.songNumber.setTextColor(defaultTextColor);
			holder.songDuration.setTextColor(defaultTextColor);
			holder.itemView.setAlpha(1.0f);
		}

		holder.itemView.setOnClickListener(v -> {
			if (selectionMode) {
				toggleSelect(song.getId());
				notifyItemChanged(holder.getBindingAdapterPosition());
			} else if (songClickListener != null) songClickListener.onSongClick(song, adapterPosition);
		});
		holder.itemView.setOnLongClickListener(v -> {
			if (longClickListener != null) { longClickListener.onItemLongClick(adapterPosition); return true; }
			return false;
		});
		// 防止点击下载区域触发整行播放
		holder.downloadContainer.setOnClickListener(v -> {});
		holder.downloadContainer.setOnLongClickListener(v -> true);

		// 正在播放指示器渲染（无动画）
		boolean isPlayingItem = currentPlayingSongId != null && currentPlayingSongId.equals(song.getId());
		holder.playingBar.setVisibility(isPlayingItem ? View.VISIBLE : View.GONE);
		        holder.playingIcon.setVisibility(View.GONE);
        if (!selectionMode) {
            holder.songTitle.setTypeface(null, isPlayingItem ? Typeface.BOLD : Typeface.NORMAL);
        }
	}

	private void setupDownloadUI(ViewHolder holder, Song song) {
		if (!showDownloadStatus) {
			holder.downloadContainer.setVisibility(View.GONE);
			return;
		}
		holder.downloadContainer.setVisibility(View.VISIBLE);
		DownloadInfo downloadInfo = downloadManager.getDownloadInfo(song.getId());
		// 重置
		holder.btnDownload.setVisibility(View.GONE);
		holder.downloadProgress.setVisibility(View.GONE);
		holder.downloadPercent.setVisibility(View.GONE);
		holder.downloadComplete.setVisibility(View.GONE);
		holder.downloadFailed.setVisibility(View.GONE);

		if (downloadInfo != null && downloadInfo.isDownloading()) {
			holder.downloadPercent.setVisibility(View.VISIBLE);
			int percent = downloadInfo.getProgressPercentage();
			holder.downloadPercent.setText(Math.max(0, percent) + "%");
			holder.downloadPercent.setOnClickListener(v -> {
				DownloadManager.getInstance(context).pauseDownload(song.getId());
				notifyItemChanged(holder.getAdapterPosition());
			});
			holder.downloadPercent.setOnLongClickListener(v -> {
				new android.app.AlertDialog.Builder(context)
					.setTitle("取消下载")
					.setMessage("确定取消当前下载吗？")
					.setPositiveButton("确定", (d, w) -> DownloadManager.getInstance(context).cancelDownload(song.getId()))
					.setNegativeButton("取消", null)
					.show();
				return true;
			});
			return;
		}
		if (downloadInfo != null && downloadInfo.isPaused()) {
			holder.downloadPercent.setVisibility(View.VISIBLE);
			int percent = downloadInfo.getProgressPercentage();
			holder.downloadPercent.setText("暂停 " + Math.max(0, percent) + "%");
			holder.downloadPercent.setOnClickListener(v -> {
				DownloadManager.getInstance(context).resumeDownload(song);
				notifyItemChanged(holder.getAdapterPosition());
			});
			holder.downloadPercent.setOnLongClickListener(v -> {
				new android.app.AlertDialog.Builder(context)
					.setTitle("取消下载")
					.setMessage("确定取消当前下载吗？")
					.setPositiveButton("确定", (d, w) -> DownloadManager.getInstance(context).cancelDownload(song.getId()))
					.setNegativeButton("取消", null)
					.show();
				return true;
			});
			return;
		}
		if (downloadInfo != null && downloadInfo.isFailed()) {
			holder.downloadFailed.setVisibility(View.VISIBLE);
			holder.downloadFailed.setOnClickListener(v -> {
				if (downloadClickListener != null) downloadClickListener.onDownloadClick(song);
			});
			return;
		}
		boolean isDownloaded = downloadedSongIds.contains(song.getId());
		if (isDownloaded) {
			holder.downloadComplete.setVisibility(View.VISIBLE);
			holder.downloadComplete.setOnClickListener(v -> {
				if (downloadClickListener != null) downloadClickListener.onDeleteDownloadClick(song);
			});
		} else {
			holder.btnDownload.setVisibility(View.VISIBLE);
			holder.btnDownload.setOnClickListener(v -> {
				if (downloadClickListener != null) downloadClickListener.onDownloadClick(song);
			});
		}
	}

	private void triggerLoadAround(int centerPosition) {
		// 加载包含 centerPosition 的页
		int aligned = Math.max(0, (centerPosition / pageSize) * pageSize);
		if (loadingOffsets.contains(aligned)) return;
		loadingOffsets.add(aligned);
		bgExecutor.execute(() -> {
			List<Song> songs = musicRepository.getSongsRange(pageSize, aligned);
			List<SongWithIndex> items = new ArrayList<>();
			for (int i = 0; i < songs.size(); i++) {
				Song s = songs.get(i);
				boolean isDownloaded = downloadedSongIds.contains(s.getId());
				boolean cachedByKey = com.watch.limusic.cache.CacheManager.getInstance(context).isCachedByAnyKey(s.getId());
				boolean cached = isDownloaded || cachedByKey;
				SongWithIndex swi = new SongWithIndex(s, aligned + i, cached);
				items.add(swi);
			}
			((android.app.Activity) context).runOnUiThread(() -> {
				for (int i = 0; i < items.size(); i++) {
					loaded.put(aligned + i, items.get(i));
					notifyItemChanged(aligned + i);
				}
			});
		});
	}

	// 在给定位置附近预取当前/前一页/后一页，避免仅显示序号
	public void prefetchAround(int position) {
		int aligned = Math.max(0, (position / pageSize) * pageSize);
		triggerLoadAround(aligned);
		if (aligned - pageSize >= 0) triggerLoadAround(aligned - pageSize);
		triggerLoadAround(aligned + pageSize);
	}

	public void prefetch(int startPosition) { triggerLoadAround(startPosition); }

	public void clearCache() {
		loaded.clear();
		loadingOffsets.clear();
		notifyDataSetChanged();
	}

	public void updateSongCacheStatus(String songId, boolean isCached) {
		for (int i = 0; i < loaded.size(); i++) {
			int key = loaded.keyAt(i);
			SongWithIndex item = loaded.valueAt(i);
			if (item != null && item.getId().equals(songId)) {
				item.setCached(isCached);
				notifyItemChanged(key);
				break;
			}
		}
	}

	public void updateSongDownloadStatus(String songId) {
		for (int i = 0; i < loaded.size(); i++) {
			int key = loaded.keyAt(i);
			SongWithIndex item = loaded.valueAt(i);
			if (item != null && item.getId().equals(songId)) {
				notifyItemChanged(key);
				break;
			}
		}
		// 同步维护内存集合
		try {
			if (songId != null && !songId.isEmpty()) {
				boolean nowDownloaded = localFileDetector.isSongDownloaded(songId);
				if (nowDownloaded) downloadedSongIds.add(songId); else downloadedSongIds.remove(songId);
			}
		} catch (Throwable ignore) {}
	}

	public void updateSongDownloadProgress(String songId, int progress) {
		for (int i = 0; i < loaded.size(); i++) {
			int key = loaded.keyAt(i);
			SongWithIndex item = loaded.valueAt(i);
			if (item != null && item.getId().equals(songId)) {
				notifyItemChanged(key, progress);
				break;
			}
		}
	}

	static class ViewHolder extends RecyclerView.ViewHolder {
		final ImageView albumArt;
		final TextView songTitle;
		final TextView songArtist;
		final TextView songDuration;
		final TextView songNumber;
		final ImageView checkbox;
		final FrameLayout downloadContainer;
		final ImageButton btnDownload;
		final ProgressBar downloadProgress;
		final TextView downloadPercent;
		final ImageView downloadComplete;
		final ImageView downloadFailed;
		final View playingBar;
		final ImageView playingIcon;

		ViewHolder(View itemView) {
			super(itemView);
			albumArt = itemView.findViewById(R.id.album_art);
			songTitle = itemView.findViewById(R.id.song_title);
			songArtist = itemView.findViewById(R.id.song_artist);
			songDuration = itemView.findViewById(R.id.song_duration);
			songNumber = itemView.findViewById(R.id.song_number);
			checkbox = itemView.findViewById(R.id.checkbox);
			downloadContainer = itemView.findViewById(R.id.download_container);
			btnDownload = itemView.findViewById(R.id.btn_download);
			downloadProgress = itemView.findViewById(R.id.download_progress);
			downloadPercent = itemView.findViewById(R.id.download_percent);
			downloadComplete = itemView.findViewById(R.id.download_complete);
			downloadFailed = itemView.findViewById(R.id.download_failed);
			playingBar = itemView.findViewById(R.id.playing_indicator_bar);
			playingIcon = itemView.findViewById(R.id.playing_icon);
		}
	}
} 