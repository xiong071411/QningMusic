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

	public AllSongsRangeAdapter(Context context, MusicRepository repository, SongAdapter.OnSongClickListener listener) {
		this.context = context;
		this.musicRepository = repository;
		this.localFileDetector = new LocalFileDetector(context);
		this.downloadManager = DownloadManager.getInstance(context);
		this.songClickListener = listener;
	}

	public void setOnDownloadClickListener(SongAdapter.OnDownloadClickListener listener) {
		this.downloadClickListener = listener;
	}

	public void setShowCoverArt(boolean show) { this.showCoverArt = show; notifyDataSetChanged(); }
	public void setShowDownloadStatus(boolean show) { this.showDownloadStatus = show; notifyDataSetChanged(); }

	public void setTotalCount(int total) {
		this.totalCount = Math.max(0, total);
		notifyDataSetChanged();
	}

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
		// 占位渲染：仅显示编号，其余置灰
		holder.songTitle.setText(" ");
		holder.songArtist.setText(" ");
		holder.songDuration.setText(" ");
		holder.songNumber.setText(String.format(Locale.getDefault(), "%02d", position + 1));
		holder.itemView.setAlpha(0.6f);
		holder.downloadContainer.setVisibility(View.GONE);
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
			if (songClickListener != null) songClickListener.onSongClick(song, adapterPosition);
		});
		// 防止点击下载区域触发整行播放
		holder.downloadContainer.setOnClickListener(v -> {});
		holder.downloadContainer.setOnLongClickListener(v -> true);
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
		boolean isDownloaded = localFileDetector.isSongDownloaded(song);
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
		new Thread(() -> {
			List<Song> songs = musicRepository.getSongsRange(pageSize, aligned);
			List<SongWithIndex> items = new ArrayList<>();
			for (int i = 0; i < songs.size(); i++) {
				Song s = songs.get(i);
				boolean cached = localFileDetector.isSongDownloaded(s) || com.watch.limusic.cache.CacheManager.getInstance(context).isCached(com.watch.limusic.api.NavidromeApi.getInstance(context).getStreamUrl(s.getId()));
				SongWithIndex swi = new SongWithIndex(s, aligned + i, cached);
				items.add(swi);
			}
			((android.app.Activity) context).runOnUiThread(() -> {
				for (int i = 0; i < items.size(); i++) {
					loaded.put(aligned + i, items.get(i));
					notifyItemChanged(aligned + i);
				}
			});
		}).start();
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
		final FrameLayout downloadContainer;
		final ImageButton btnDownload;
		final ProgressBar downloadProgress;
		final TextView downloadPercent;
		final ImageView downloadComplete;
		final ImageView downloadFailed;

		ViewHolder(View itemView) {
			super(itemView);
			albumArt = itemView.findViewById(R.id.album_art);
			songTitle = itemView.findViewById(R.id.song_title);
			songArtist = itemView.findViewById(R.id.song_artist);
			songDuration = itemView.findViewById(R.id.song_duration);
			songNumber = itemView.findViewById(R.id.song_number);
			downloadContainer = itemView.findViewById(R.id.download_container);
			btnDownload = itemView.findViewById(R.id.btn_download);
			downloadProgress = itemView.findViewById(R.id.download_progress);
			downloadPercent = itemView.findViewById(R.id.download_percent);
			downloadComplete = itemView.findViewById(R.id.download_complete);
			downloadFailed = itemView.findViewById(R.id.download_failed);
		}
	}
} 