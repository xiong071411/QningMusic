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

    // 字母索引映射，存储每个字母在列表中的位置
    private final Map<String, Integer> letterPositionMap = new HashMap<>();

    public interface OnSongClickListener {
        void onSongClick(Song song, int position);
    }

    public interface OnDownloadClickListener {
        void onDownloadClick(Song song);
        void onDeleteDownloadClick(Song song);
    }

    private OnDownloadClickListener downloadListener;

    public SongAdapter(Context context, OnSongClickListener listener) {
        super(DIFF_CALLBACK);
        this.context = context;
        this.listener = listener;
        this.cacheDetector = new CacheDetector(context);
        this.musicRepository = MusicRepository.getInstance(context);
        this.downloadManager = DownloadManager.getInstance(context);
        this.localFileDetector = new LocalFileDetector(context);
    }

    public void setOnDownloadClickListener(OnDownloadClickListener listener) {
        this.downloadListener = listener;
    }

    public void setShowCoverArt(boolean showCoverArt) {
        this.showCoverArt = showCoverArt;
        // ListAdapter 会处理更新，但这里我们改变的是所有 ViewHolder 的行为，
        // 所以需要一个完整的重绘。
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
        return position != null ? position : -1; // -1 表示没找到
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
        Collections.sort(sortedLetters); // 确保字母有序
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
        
        // 设置下载状态和UI
        setupDownloadUI(holder, song);

        // 设置离线模式下的视觉效果
        boolean isOfflineMode = !NetworkUtils.isNetworkAvailable(context);
        boolean isDownloaded = songItem.isCached();

        if (isOfflineMode) {
            // 离线模式下，只有已下载的歌曲显示正常颜色
            int textColor = isDownloaded
                ? ContextCompat.getColor(context, R.color.textPrimary)
                : ContextCompat.getColor(context, R.color.textDisabled);

            holder.songTitle.setTextColor(textColor);
            holder.songArtist.setTextColor(textColor);
            holder.songNumber.setTextColor(textColor);
            holder.songDuration.setTextColor(textColor);

            // 设置透明度：未下载的歌曲整体透明度降低
            holder.itemView.setAlpha(isDownloaded ? 1.0f : 0.7f);
        } else {
            // 在线模式下，恢复默认颜色和透明度
            int defaultTextColor = ContextCompat.getColor(context, R.color.textPrimary);
            holder.songTitle.setTextColor(defaultTextColor);
            holder.songArtist.setTextColor(defaultTextColor);
            holder.songNumber.setTextColor(defaultTextColor);
            holder.songDuration.setTextColor(defaultTextColor);
            holder.itemView.setAlpha(1.0f);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSongClick(song, holder.getAdapterPosition());
            }
        });

        // 防止点击下载区域触发整行播放
        holder.downloadContainer.setOnClickListener(v -> {});
        holder.downloadContainer.setOnLongClickListener(v -> true);
    }

    /**
     * 设置下载相关UI状态
     */
    private void setupDownloadUI(ViewHolder holder, Song song) {
        if (!showDownloadStatus) {
            holder.downloadContainer.setVisibility(View.GONE);
            return;
        }

        holder.downloadContainer.setVisibility(View.VISIBLE);

        // 先检查是否在下载队列中
        DownloadInfo downloadInfo = downloadManager.getDownloadInfo(song.getId());

        // 重置所有状态
        holder.btnDownload.setVisibility(View.GONE);
        holder.downloadProgress.setVisibility(View.GONE);
        holder.downloadPercent.setVisibility(View.GONE);
        holder.downloadComplete.setVisibility(View.GONE);
        holder.downloadFailed.setVisibility(View.GONE);

        if (downloadInfo != null && downloadInfo.isDownloading()) {
            // 下载中：仅显示百分比，隐藏其他
            holder.downloadProgress.setVisibility(View.GONE);
            holder.downloadPercent.setVisibility(View.VISIBLE);
            int percent = downloadInfo.getProgressPercentage();
            if (percent > 0) {
                holder.downloadPercent.setText(percent + "%");
            } else {
                holder.downloadPercent.setText("0%");
            }
            // 点击=暂停；长按=取消
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
            // 暂停：显示“暂停 xx%”，点击继续，长按取消
            holder.downloadProgress.setVisibility(View.GONE);
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
            // 下载失败状态
            holder.downloadFailed.setVisibility(View.VISIBLE);
            holder.downloadFailed.setOnClickListener(v -> {
                if (downloadListener != null) {
                    downloadListener.onDownloadClick(song); // 重试下载
                }
            });
            return;
        }

        // 未在下载中或失败，检查是否已完整下载
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
    
    /**
     * 更新歌曲的缓存状态
     */
    public void updateSongCacheStatus(String songId, boolean isCached) {
        // 遍历当前列表，找到匹配的歌曲并更新状态
        List<SongWithIndex> currentList = new ArrayList<>(getCurrentList());
        for (int i = 0; i < currentList.size(); i++) {
            SongWithIndex item = currentList.get(i);
            if (item.getId().equals(songId)) {
                item.setCached(isCached);
                notifyItemChanged(i);
                break;
            }
        }
        
        // 同时更新数据库中的状态
        musicRepository.updateSongCacheStatus(songId, isCached);
    }

    /**
     * 更新歌曲的下载状态显示
     */
    public void updateSongDownloadStatus(String songId) {
        // 遍历当前列表，找到匹配的歌曲并更新UI
        List<SongWithIndex> currentList = new ArrayList<>(getCurrentList());
        for (int i = 0; i < currentList.size(); i++) {
            SongWithIndex item = currentList.get(i);
            if (item.getId().equals(songId)) {
                notifyItemChanged(i);
                break;
            }
        }
    }

    /**
     * 获取指定位置的歌曲项（公共访问方法，解决ListAdapter.getItem是protected的问题）
     */
    public SongWithIndex getSongItemAt(int position) {
        return getItem(position);
    }
    
    // 新增方法，用于处理从 MainActivity 传递过来的原始歌曲列表
    public void processAndSubmitList(List<Song> songs) {
        // 在后台线程处理数据转换和排序
        new Thread(() -> {
            List<Song> source = songs != null ? songs : Collections.emptyList();

            // 离线模式下先做一次基于“歌曲键”的去重，并优先保留可离线播放的项
            boolean offline = !NetworkUtils.isNetworkAvailable(context);
            List<Song> deduped;
            if (offline) {
                // key: title|artist|album（全部小写去空格）；按插入顺序保留
                Map<String, Song> map = new LinkedHashMap<>();
                for (Song s : source) {
                    if (s == null) continue;
                    String key = buildSongDedupKey(s);

                    boolean playable = localFileDetector.isSongDownloaded(s) || cacheDetector.isSongCached(s.getId());
                    if (!map.containsKey(key)) {
                        // 首次放入
                        map.put(key, s);
                    } else if (playable) {
                        // 如果已有同键且当前项可离线播放，则用当前项覆盖，优先展示可用版本
                        map.put(key, s);
                    }
                }
                // 仅在离线模式下：如果你希望列表只显示“可离线播放”的歌曲，可以在这里再过滤一遍
                // 但为了不改变既有“灰显不可用歌曲”的体验，这里保留全部去重后的歌曲
                deduped = new ArrayList<>(map.values());
            } else {
                deduped = new ArrayList<>(source);
            }

            // 1. 转换并排序
            ArrayList<SongWithIndex> songItems = new ArrayList<>();
            for (int i = 0; i < deduped.size(); i++) {
                Song song = deduped.get(i);
                // 检查每首歌曲是否可离线播放：已下载 或 已缓存
                boolean isPlayableOffline = localFileDetector.isSongDownloaded(song) || cacheDetector.isSongCached(song.getId());
                songItems.add(new SongWithIndex(song, i, isPlayableOffline));
            }
            Collections.sort(songItems);

            // 2. 更新排序后的位置和编号
            for (int i = 0; i < songItems.size(); i++) {
                songItems.get(i).setPosition(i);
            }

            // 3. 更新字母索引映射
            updateLetterPositionMap(songItems);

            // 4. 在主线程提交列表
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                submitList(songItems);
            });
        }).start();
    }
    
    /**
     * 刷新所有歌曲的缓存状态
     */
    public void refreshCacheStatus() {
        new Thread(() -> {
            List<SongWithIndex> currentList = new ArrayList<>(getCurrentList());
            boolean hasChanges = false;
            
            // 检查每首歌的缓存状态
            for (SongWithIndex item : currentList) {
                Song song = item.getSong();
                boolean isPlayableOffline = localFileDetector.isSongDownloaded(song) || cacheDetector.isSongCached(song.getId());
                if (item.isCached() != isPlayableOffline) {
                    item.setCached(isPlayableOffline);
                    hasChanges = true;
                }
            }
            
            // 如果有变化，提交新列表
            if (hasChanges) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    submitList(new ArrayList<>(currentList));
                });
            }
        }).start();
    }

    // 获取原始歌曲列表（不带索引的）
    public List<Song> getSongList() {
        List<Song> songs = new ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) {
            songs.add(getItem(i).getSong());
        }
        return songs;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView albumArt;
        final TextView songTitle;
        final TextView songArtist;
        final TextView songDuration;
        final TextView songNumber;

        // 下载相关UI元素
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

            // 初始化下载相关UI元素
            downloadContainer = itemView.findViewById(R.id.download_container);
            btnDownload = itemView.findViewById(R.id.btn_download);
            downloadProgress = itemView.findViewById(R.id.download_progress);
            downloadPercent = itemView.findViewById(R.id.download_percent);
            downloadComplete = itemView.findViewById(R.id.download_complete);
            downloadFailed = itemView.findViewById(R.id.download_failed);
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