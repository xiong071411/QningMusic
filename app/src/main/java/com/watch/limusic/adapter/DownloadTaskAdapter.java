package com.watch.limusic.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;
import com.watch.limusic.model.DownloadInfo;
import com.watch.limusic.model.DownloadStatus;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class DownloadTaskAdapter extends ListAdapter<DownloadInfo, DownloadTaskAdapter.VH> {
    private final Context context;

    public DownloadTaskAdapter(Context ctx) {
        super(DIFF);
        this.context = ctx.getApplicationContext();
        setHasStableIds(true);
    }

    private static final DiffUtil.ItemCallback<DownloadInfo> DIFF = new DiffUtil.ItemCallback<DownloadInfo>() {
        @Override
        public boolean areItemsTheSame(@NonNull DownloadInfo oldItem, @NonNull DownloadInfo newItem) {
            return oldItem.getSongId() != null && oldItem.getSongId().equals(newItem.getSongId());
        }

        @Override
        public boolean areContentsTheSame(@NonNull DownloadInfo oldItem, @NonNull DownloadInfo newItem) {
            // 更新条件：状态变化、已下/总字节变化、错误信息变化
            return oldItem.getStatus() == newItem.getStatus()
                    && oldItem.getDownloadedBytes() == newItem.getDownloadedBytes()
                    && oldItem.getTotalBytes() == newItem.getTotalBytes()
                    && eq(oldItem.getErrorMessage(), newItem.getErrorMessage())
                    && eq(oldItem.getTitle(), newItem.getTitle())
                    && eq(oldItem.getArtist(), newItem.getArtist())
                    && eq(oldItem.getAlbum(), newItem.getAlbum());
        }

        private boolean eq(Object a, Object b) { return a == b || (a != null && a.equals(b)); }
    };

    @Override
    public long getItemId(int position) {
        DownloadInfo di = getItem(position);
        return di != null && di.getSongId() != null ? di.getSongId().hashCode() : position;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_task, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DownloadInfo info = getItem(position);
        if (info == null) return;
        h.title.setText(nonNull(info.getTitle()));
        if (info.getStatus() == DownloadStatus.DOWNLOAD_FAILED) {
            h.subtitle.setVisibility(View.VISIBLE);
            h.subtitle.setText("下载失败，可重试");
        } else if (info.getStatus() == DownloadStatus.WAITING) {
            h.subtitle.setVisibility(View.VISIBLE);
            h.subtitle.setText("等待中");
        } else {
            // 下载中/暂停等状态下不显示副标题，保持紧凑
            h.subtitle.setVisibility(View.GONE);
        }

        // bytes text: 已下载/总大小 e.g. 1.1MB/7.9MB; total unknown 显示 已下 XMB/—
        String bytesStr;
        long dl = info.getDownloadedBytes();
        long total = info.getTotalBytes();
        if (total > 0) {
            bytesStr = formatSize(dl) + "/" + formatSize(total);
        } else {
            bytesStr = formatSize(dl) + "/—";
        }
        h.bytes.setText(bytesStr);

        // Thin progress bar logic
        if (info.getStatus() == DownloadStatus.DOWNLOAD_FAILED) {
            h.progress.setVisibility(View.GONE);
        } else {
            h.progress.setVisibility(View.VISIBLE);
            int percent = info.getStatus() == DownloadStatus.WAITING ? 0 : info.getProgressPercentage();
            h.progress.setProgress(Math.max(0, Math.min(100, percent)));
            int colorRes;
            if (info.getStatus() == DownloadStatus.DOWNLOAD_PAUSED) colorRes = R.color.download_progress_paused;
            else if (info.getStatus() == DownloadStatus.WAITING) colorRes = R.color.download_progress_running;
            else colorRes = R.color.download_progress_running;
            int color = ContextCompat.getColor(context, colorRes);
            try {
                h.progress.setProgressTintList(ColorStateList.valueOf(color));
                h.progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x20FFFFFF));
            } catch (Exception ignore) {}
        }

        // Right action: pause/resume/retry
        if (h.action != null) {
            if (info.getStatus() == DownloadStatus.DOWNLOADING) {
                h.action.setImageResource(R.drawable.ic_pause_rounded);
                // 统一使用运行中颜色
                try { h.action.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.download_progress_running))); } catch (Exception ignore) {}
                h.action.setContentDescription("暂停");
                h.action.setOnClickListener(v -> {
                    try { com.watch.limusic.download.DownloadManager.getInstance(v.getContext()).pauseDownload(info.getSongId()); } catch (Exception ignore) {}
                    // 立即切换本地UI为“暂停”态，等待广播二次校正
                    try {
                        DownloadInfo clone = new DownloadInfo(info);
                        clone.setStatus(DownloadStatus.DOWNLOAD_PAUSED);
                        upsert(clone);
                    } catch (Exception ignore) {}
                });
            } else if (info.getStatus() == DownloadStatus.DOWNLOAD_PAUSED) {
                h.action.setImageResource(R.drawable.ic_play_rounded);
                // 统一使用运行中颜色（保持一致性）
                try { h.action.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.download_progress_running))); } catch (Exception ignore) {}
                h.action.setContentDescription("继续");
                h.action.setOnClickListener(v -> {
                    try {
                        com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(v.getContext()).songDao().getSongById(info.getSongId());
                        if (se != null) {
                            com.watch.limusic.model.Song s = new com.watch.limusic.model.Song(se.getId(), se.getTitle(), se.getArtist(), se.getAlbum(), se.getCoverArt(), se.getStreamUrl(), se.getDuration());
                            s.setAlbumId(se.getAlbumId());
                            com.watch.limusic.download.DownloadManager.getInstance(v.getContext()).resumeDownload(s);
                        }
                    } catch (Exception ignore) {}
                    // 立即切换本地UI为“下载中”态，等待广播二次校正
                    try {
                        DownloadInfo clone = new DownloadInfo(info);
                        clone.setStatus(DownloadStatus.DOWNLOADING);
                        upsert(clone);
                    } catch (Exception ignore) {}
                });
            } else if (info.getStatus() == DownloadStatus.WAITING) {
                h.action.setImageResource(R.drawable.ic_pause_rounded);
                try { h.action.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.download_progress_running))); } catch (Exception ignore) {}
                h.action.setContentDescription("等待中");
                h.action.setOnClickListener(v -> {});
            } else if (info.getStatus() == DownloadStatus.DOWNLOAD_FAILED) {
                h.action.setImageResource(R.drawable.ic_download);
                try { h.action.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.download_progress_running))); } catch (Exception ignore) {}
                h.action.setContentDescription("重试");
                h.action.setOnClickListener(v -> {
                    try {
                        com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(v.getContext()).songDao().getSongById(info.getSongId());
                        if (se != null) {
                            com.watch.limusic.model.Song s = new com.watch.limusic.model.Song(se.getId(), se.getTitle(), se.getArtist(), se.getAlbum(), se.getCoverArt(), se.getStreamUrl(), se.getDuration());
                            s.setAlbumId(se.getAlbumId());
                            com.watch.limusic.download.DownloadManager.getInstance(v.getContext()).resumeDownload(s);
                        }
                    } catch (Exception ignore) {}
                    try {
                        DownloadInfo clone = new DownloadInfo(info);
                        clone.setStatus(DownloadStatus.DOWNLOADING);
                        upsert(clone);
                    } catch (Exception ignore) {}
                });
            } else {
                h.action.setOnClickListener(null);
            }
            // 长按单项按钮：取消下载（对下载中/暂停/失败均可）
            if (h.action != null) {
                h.action.setOnLongClickListener(v -> {
                    new androidx.appcompat.app.AlertDialog.Builder(v.getContext())
                        .setTitle("取消下载")
                        .setMessage("确定取消当前下载吗？")
                        .setPositiveButton("确定", (d,w) -> {
                            try { com.watch.limusic.download.DownloadManager.getInstance(v.getContext()).cancelDownload(info.getSongId()); } catch (Exception ignore) {}
                            // 取消后本地状态立即切到 NOT_DOWNLOADED，交由 refresh 合并
                            try {
                                com.watch.limusic.model.DownloadInfo clone = new com.watch.limusic.model.DownloadInfo(info);
                                clone.setStatus(com.watch.limusic.model.DownloadStatus.NOT_DOWNLOADED);
                                upsert(clone);
                            } catch (Exception ignore) {}
                        })
                        .setNegativeButton("取消", null)
                        .show();
                    return true;
                });
            }
        }
    }

    public void upsert(DownloadInfo info) {
        if (info == null || info.getSongId() == null) return;
        List<DownloadInfo> cur = new ArrayList<>(getCurrentList());
        int idx = indexOf(cur, info.getSongId());
        if (idx >= 0) cur.set(idx, info); else cur.add(0, info);
        submitList(cur);
    }

    public void removeBySongId(String songId) {
        if (songId == null) return;
        List<DownloadInfo> cur = new ArrayList<>(getCurrentList());
        int idx = indexOf(cur, songId);
        if (idx >= 0) { cur.remove(idx); submitList(cur); }
    }

    public List<DownloadInfo> getSnapshot() {
        return new ArrayList<>(getCurrentList());
    }

    private int indexOf(List<DownloadInfo> list, String songId) {
        for (int i = 0; i < list.size(); i++) {
            DownloadInfo d = list.get(i);
            if (d != null && songId.equals(d.getSongId())) return i;
        }
        return -1;
    }

    private static String nonNull(String s) { return s != null ? s : ""; }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0B";
        final String[] units = {"B","KB","MB","GB","TB"};
        int group = (int) (Math.log10(bytes) / Math.log10(1024));
        if (group < 0) group = 0; if (group >= units.length) group = units.length - 1;
        double val = bytes / Math.pow(1024, group);
        return new DecimalFormat("#,##0.#").format(val) + units[group];
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final TextView bytes;
        final ProgressBar progress;
        final ImageButton action;
        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_title);
            subtitle = itemView.findViewById(R.id.text_subtitle);
            bytes = itemView.findViewById(R.id.text_bytes);
            progress = itemView.findViewById(R.id.progress_thin);
            action = itemView.findViewById(R.id.btn_action);
        }
    }
} 