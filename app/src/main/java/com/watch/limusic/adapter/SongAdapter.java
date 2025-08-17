package com.watch.limusic.adapter;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.FrameLayout;
import android.graphics.Color;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.watch.limusic.R;
import com.watch.limusic.database.CacheDetector;
import com.watch.limusic.model.Song;
import com.watch.limusic.model.SongWithIndex;
import com.watch.limusic.api.NavidromeApi;
import com.watch.limusic.database.MusicRepository;
import com.watch.limusic.download.DownloadManager;
import com.watch.limusic.download.LocalFileDetector;
import com.watch.limusic.model.DownloadInfo;
import com.watch.limusic.model.DownloadStatus;
import com.watch.limusic.util.NetworkUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import android.util.Log;
import java.util.LinkedHashMap;

public class SongAdapter extends ListAdapter<SongWithIndex, SongAdapter.ViewHolder> {
    private static final String TAG = "SongAdapter";
    private final Context context;
    private final OnSongClickListener listener;
    private boolean showCoverArt = true;
    private boolean showCacheStatus = true;
    private boolean showDownloadStatus = true;
    private CacheDetector cacheDetector;
    private MusicRepository musicRepository;
    private DownloadManager downloadManager;
    private LocalFileDetector localFileDetector;
    private boolean isPlaylistDetail = false;

    public interface OnStartDragListener { void onStartDrag(RecyclerView.ViewHolder vh); }
    private boolean showDragHandle = false;
    private OnStartDragListener dragListener;

    public interface OnSongLongClickListener { void onSongLongClick(int position); }
    private OnSongLongClickListener longClickListener;

    private final Map<String, Integer> letterPositionMap = new HashMap<>();

    // 选择模式支持
    private boolean selectionMode = false;
    private final java.util.LinkedHashSet<String> selectedIds = new java.util.LinkedHashSet<>();

    public interface OnSongClickListener {
        void onSongClick(Song song, int position);
    }

    public interface OnDownloadClickListener {
        void onDownloadClick(Song song);
        void onDeleteDownloadClick(Song song);
    }

    private OnDownloadClickListener downloadListener;

    private final java.util.ArrayList<SongWithIndex> backingList = new java.util.ArrayList<>();

    public SongAdapter(Context context, OnSongClickListener listener) {
        super(DIFF_CALLBACK);
        this.context = context;
        this.listener = listener;
        this.cacheDetector = new CacheDetector(context);
        this.musicRepository = MusicRepository.getInstance(context);
        this.downloadManager = DownloadManager.getInstance(context);
        this.localFileDetector = new LocalFileDetector(context);
        setHasStableIds(true);
    }

    public void setOnDownloadClickListener(OnDownloadClickListener listener) {
        this.downloadListener = listener;
    }

    public void setOnItemLongClickListener(OnSongLongClickListener l) { this.longClickListener = l; }

    public void setSelectionMode(boolean enable) {
        this.selectionMode = enable;
        if (!enable) selectedIds.clear();
        notifyDataSetChanged();
    }
    public boolean isSelectionMode() { return selectionMode; }
    public interface OnSelectionChanged { void onSelectionCountChanged(int count); }
    private OnSelectionChanged selectionChangedListener;
    public void setOnSelectionChangedListener(OnSelectionChanged l) { this.selectionChangedListener = l; }
    public void toggleSelect(String songId) {
        if (!selectionMode || songId == null) return;
        if (selectedIds.contains(songId)) selectedIds.remove(songId); else selectedIds.add(songId);
        if (selectionChangedListener != null) selectionChangedListener.onSelectionCountChanged(selectedIds.size());
        // 仅刷新受影响的这一项
        int idx = -1;
        for (int i = 0; i < getItemCount(); i++) {
            if (getItem(i).getId().equals(songId)) { idx = i; break; }
        }
        if (idx >= 0) notifyItemChanged(idx);
    }
    public void clearSelection() { selectedIds.clear(); notifyDataSetChanged(); }
    public java.util.List<String> getSelectedIdsInOrder() { return new java.util.ArrayList<>(selectedIds); }

    public void setShowCoverArt(boolean showCoverArt) {
        this.showCoverArt = showCoverArt;
        notifyDataSetChanged();
    }
    
    public void setShowCacheStatus(boolean showCacheStatus) {
        this.showCacheStatus = showCacheStatus;
        notifyDataSetChanged();
    }

    public void setShowDownloadStatus(boolean showDownloadStatus) {
        this.showDownloadStatus = showDownloadStatus;
        notifyDataSetChanged();
    }

    public void setShowDragHandle(boolean show, OnStartDragListener l) {
        this.showDragHandle = show;
        this.dragListener = l;
        notifyDataSetChanged();
    }

    public void setPlaylistDetail(boolean v) { this.isPlaylistDetail = v; notifyDataSetChanged(); }

    public int getPositionForLetter(String letter) {
        if ("#".equals(letter)) {
            for (int i = 0; i < getItemCount(); i++) {
                String firstChar = getItem(i).getSortLetter();
                if ("#".equals(firstChar) || Character.isDigit(firstChar.charAt(0))) {
                    return i;
                }
            }
            return 0;
        }
        Integer position = letterPositionMap.get(letter);
        return position != null ? position : -1;
    }

    public List<String> getAvailableIndexLetters() {
        Set<String> simplifiedLetters = new HashSet<>();
        for (String key : letterPositionMap.keySet()) {
            if ("#".equals(key) || Character.isDigit(key.charAt(0))) {
                simplifiedLetters.add("#");
            } else {
                simplifiedLetters.add(key);
            }
        }
        List<String> sortedLetters = new ArrayList<>(simplifiedLetters);
        Collections.sort(sortedLetters);
        return sortedLetters;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SongWithIndex songItem = getItem(position);
        Song song = songItem.getSong();

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

        if (showCoverArt) {
            holder.albumArt.setVisibility(View.VISIBLE);
            String albumId = song.getAlbumId();
            String key = (albumId != null && !albumId.isEmpty()) ? albumId : song.getCoverArtUrl();
            String localCover = (albumId != null && !albumId.isEmpty()) ? localFileDetector.getDownloadedAlbumCoverPath(albumId) : null;
            String coverArtUrl = (localCover != null) ? ("file://" + localCover) : NavidromeApi.getInstance(context).getCoverArtUrl(key);
            boolean isLocal = coverArtUrl != null && coverArtUrl.startsWith("file://");

            if (isLocal) {
                Glide.with(context)
                        .load(coverArtUrl)
                        .override(100, 100)
                        .placeholder(R.drawable.default_album_art)
                        .error(R.drawable.default_album_art)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(holder.albumArt);
            } else {
            Glide.with(context)
                    .load(coverArtUrl)
                    .override(100, 100)
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                        .signature(new ObjectKey(key != null ? key : ""))
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .into(holder.albumArt);
            }
        } else {
            holder.albumArt.setVisibility(View.GONE);
        }
        
        setupDownloadUI(holder, song);

        boolean isOfflineMode = !NetworkUtils.isNetworkAvailable(context);
        boolean isDownloaded = songItem.isCached();

        if (isOfflineMode) {
            int textColor = isDownloaded
                ? ContextCompat.getColor(context, R.color.textPrimary)
                : ContextCompat.getColor(context, R.color.textDisabled);

            holder.songTitle.setTextColor(textColor);
            holder.songArtist.setTextColor(textColor);
            holder.songNumber.setTextColor(textColor);
            holder.songDuration.setTextColor(textColor);

            holder.itemView.setAlpha(isDownloaded ? 1.0f : 0.7f);
        } else {
            int defaultTextColor = ContextCompat.getColor(context, R.color.textPrimary);
            holder.songTitle.setTextColor(defaultTextColor);
            holder.songArtist.setTextColor(defaultTextColor);
            holder.songNumber.setTextColor(defaultTextColor);
            holder.songDuration.setTextColor(defaultTextColor);
            holder.itemView.setAlpha(1.0f);
        }

        // 选择模式下：隐藏下载区域，仅显示拖动柄；退出后恢复
        if (selectionMode && isPlaylistDetail) {
            holder.downloadContainer.setVisibility(View.GONE);
            holder.dragHandle.setVisibility(showDragHandle ? View.VISIBLE : View.GONE);
        } else {
            holder.dragHandle.setVisibility(View.GONE);
            holder.downloadContainer.setVisibility(View.VISIBLE);
        }

        // 扩大拖动柄的触摸范围，仅按下时启动拖动，不拦截后续事件
        holder.dragHandle.setOnTouchListener((v, e) -> {
            if (showDragHandle && selectionMode && isPlaylistDetail && dragListener != null) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    dragListener.onStartDrag(holder);
                    return true; // 消耗按下事件，其余事件交还给 RecyclerView/ItemTouchHelper
                }
            }
            return false;
        });

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelect(song.getId());
            } else if (listener != null) {
                listener.onSongClick(song, holder.getAdapterPosition());
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onSongLongClick(holder.getBindingAdapterPosition());
                return true;
            }
            return false;
        });

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

        holder.btnDownload.setVisibility(View.GONE);
        holder.downloadProgress.setVisibility(View.GONE);
        holder.downloadPercent.setVisibility(View.GONE);
        holder.downloadComplete.setVisibility(View.GONE);
        holder.downloadFailed.setVisibility(View.GONE);

        if (downloadInfo != null && downloadInfo.isDownloading()) {
            holder.downloadPercent.setVisibility(View.VISIBLE);
            int percent = downloadInfo.getProgressPercentage();
            if (percent > 0) {
                holder.downloadPercent.setText(percent + "%");
            } else {
                holder.downloadPercent.setText("0%");
            }
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
            holder.downloadPercent.setText("暂停 " + percent + "%");
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
                if (downloadListener != null) {
                    downloadListener.onDownloadClick(song);
                }
            });
            return;
        }

        boolean isDownloaded = localFileDetector.isSongDownloaded(song);
        if (isDownloaded) {
            holder.downloadComplete.setVisibility(View.VISIBLE);
            holder.downloadComplete.setOnClickListener(v -> {
                if (downloadListener != null) {
                    downloadListener.onDeleteDownloadClick(song);
                }
            });
        } else {
            holder.btnDownload.setVisibility(View.VISIBLE);
            holder.btnDownload.setOnClickListener(v -> {
                if (downloadListener != null) {
                    downloadListener.onDownloadClick(song);
                }
            });
        }
    }

    private void updateLetterPositionMap(List<SongWithIndex> songs) {
        letterPositionMap.clear();
        for (int i = 0; i < songs.size(); i++) {
            String letter = songs.get(i).getSortLetter();
            if (!letterPositionMap.containsKey(letter)) {
                letterPositionMap.put(letter, i);
            }
        }
    }

    private static String buildSongDedupKey(Song s) {
        String t = s.getTitle() != null ? s.getTitle().trim().toLowerCase(Locale.getDefault()) : "";
        String r = s.getArtist() != null ? s.getArtist().trim().toLowerCase(Locale.getDefault()) : "";
        String a = s.getAlbum() != null ? s.getAlbum().trim().toLowerCase(Locale.getDefault()) : "";
        return t + "|" + r + "|" + a;
    }
    
    public void updateSongCacheStatus(String songId, boolean isCached) {
        List<SongWithIndex> currentList = new ArrayList<>(getCurrentList());
        for (int i = 0; i < currentList.size(); i++) {
            SongWithIndex item = currentList.get(i);
            if (item.getId().equals(songId)) {
                item.setCached(isCached);
                notifyItemChanged(i);
                break;
            }
        }
        musicRepository.updateSongCacheStatus(songId, isCached);
    }

    public void updateSongDownloadStatus(String songId) {
        List<SongWithIndex> currentList = new ArrayList<>(getCurrentList());
        for (int i = 0; i < currentList.size(); i++) {
            SongWithIndex item = currentList.get(i);
            if (item.getId().equals(songId)) {
                notifyItemChanged(i);
                break;
            }
        }
    }

    public SongWithIndex getSongItemAt(int position) {
        return getItem(position);
    }
    
    public void processAndSubmitList(List<Song> songs) {
        new Thread(() -> {
            List<Song> source = songs != null ? songs : Collections.emptyList();

            boolean offline = !NetworkUtils.isNetworkAvailable(context);
            List<Song> deduped;
            if (offline) {
                Map<String, Song> map = new LinkedHashMap<>();
                for (Song s : source) {
                    if (s == null) continue;
                    String key = buildSongDedupKey(s);

                    boolean playable = localFileDetector.isSongDownloaded(s) || cacheDetector.isSongCached(s.getId());
                    if (!map.containsKey(key)) {
                        map.put(key, s);
                    } else if (playable) {
                        map.put(key, s);
                    }
                }
                deduped = new ArrayList<>(map.values());
            } else {
                deduped = new ArrayList<>(source);
            }

            ArrayList<SongWithIndex> songItems = new ArrayList<>();
            for (int i = 0; i < deduped.size(); i++) {
                Song song = deduped.get(i);
                boolean isPlayableOffline = localFileDetector.isSongDownloaded(song) || cacheDetector.isSongCached(song.getId());
                songItems.add(new SongWithIndex(song, i, isPlayableOffline));
            }
            Collections.sort(songItems);

            for (int i = 0; i < songItems.size(); i++) {
                songItems.get(i).setPosition(i);
            }

            updateLetterPositionMap(songItems);

            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                submitList(songItems);
            });
        }).start();
    }
    
    public void refreshCacheStatus() {
        new Thread(() -> {
            List<SongWithIndex> currentList = new ArrayList<>(getCurrentList());
            boolean hasChanges = false;
            
            for (SongWithIndex item : currentList) {
                Song song = item.getSong();
                boolean isPlayableOffline = localFileDetector.isSongDownloaded(song) || cacheDetector.isSongCached(song.getId());
                if (item.isCached() != isPlayableOffline) {
                    item.setCached(isPlayableOffline);
                    hasChanges = true;
                }
            }
            
            if (hasChanges) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    submitList(new ArrayList<>(currentList));
                });
            }
        }).start();
    }

    @Override
    public long getItemId(int position) {
        SongWithIndex it = position >= 0 && position < getItemCount() ? getItem(position) : null;
        if (it == null) return RecyclerView.NO_ID;
        // 使用歌曲id的hash稳定化（注意避免碰撞概率极小）
        return it.getId().hashCode();
    }

    @Override
    public void submitList(java.util.List<SongWithIndex> list) {
        super.submitList(list);
        backingList.clear();
        if (list != null) backingList.addAll(list);
    }

    public java.util.List<Song> getSongList() {
        java.util.List<Song> songs = new java.util.ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) {
            songs.add(getItem(i).getSong());
        }
        return songs;
    }

    public void processAndSubmitListKeepOrder(List<Song> songs) {
        new Thread(() -> {
            List<Song> source = songs != null ? songs : Collections.emptyList();
            ArrayList<SongWithIndex> songItems = new ArrayList<>();
            for (int i = 0; i < source.size(); i++) {
                Song song = source.get(i);
                boolean isPlayableOffline = localFileDetector.isSongDownloaded(song) || cacheDetector.isSongCached(song.getId());
                SongWithIndex swi = new SongWithIndex(song, i, isPlayableOffline);
                swi.setPosition(i);
                songItems.add(swi);
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> submitList(songItems));
        }).start();
    }

    public void moveItem(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= getItemCount() || to >= getItemCount()) return;
        java.util.Collections.swap(backingList, from, to);
        notifyItemMoved(from, to);
    }

    public void commitOrderSnapshot() {
        // 将当前 backingList 的顺序提交为适配器数据，确保与视觉顺序一致
        submitList(new java.util.ArrayList<>(backingList));
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
        final ImageView dragHandle;

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
            dragHandle = itemView.findViewById(R.id.drag_handle);
        }
    }

    private static final DiffUtil.ItemCallback<SongWithIndex> DIFF_CALLBACK = new DiffUtil.ItemCallback<SongWithIndex>() {
        @Override
        public boolean areItemsTheSame(@NonNull SongWithIndex oldItem, @NonNull SongWithIndex newItem) {
            return oldItem.getId().equals(newItem.getId());
        }

        @Override
        public boolean areContentsTheSame(@NonNull SongWithIndex oldItem, @NonNull SongWithIndex newItem) {
            return oldItem.equals(newItem);
        }
    };

    /**
     * 更新某首歌的进度百分比显示
     */
    public void updateSongDownloadProgress(String songId, int progress) {
        List<SongWithIndex> currentList = new ArrayList<>(getCurrentList());
        for (int i = 0; i < currentList.size(); i++) {
            SongWithIndex item = currentList.get(i);
            if (item.getId().equals(songId)) {
                // 直接刷新该条目，onBind 时会显示文本
                notifyItemChanged(i, progress);
                break;
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            Object payload = payloads.get(0);
            if (payload instanceof Integer) {
                int progress = (Integer) payload;
                // 仅更新进度相关UI，减少全量重绑
                holder.downloadContainer.setVisibility(View.VISIBLE);
                holder.btnDownload.setVisibility(View.GONE);
                holder.downloadFailed.setVisibility(View.GONE);
                holder.downloadComplete.setVisibility(View.GONE);
                holder.downloadProgress.setVisibility(View.GONE);
                holder.downloadPercent.setVisibility(View.VISIBLE);
                holder.downloadPercent.setText(progress + "%");
                // 绑定暂停/取消手势
                final Song song = getItem(position).getSong();
                holder.downloadPercent.setOnClickListener(v -> {
                    DownloadManager.getInstance(context).pauseDownload(song.getId());
                    notifyItemChanged(position);
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
        }
        super.onBindViewHolder(holder, position, payloads);
    }
}