package com.watch.limusic.adapter;

import android.content.Context;
import androidx.annotation.NonNull;
import android.view.View;
import android.widget.TextView;
import com.watch.limusic.download.LocalFileDetector;
import java.util.Locale;

import com.watch.limusic.model.Song;
import com.watch.limusic.model.SongWithIndex;

import java.util.List;

/**
 * 复用 SongAdapter 展示已下载歌曲（隐藏封面与下载按钮）
 */
public class DownloadedSongAdapter extends SongAdapter {
    private final LocalFileDetector localFileDetector;
    public DownloadedSongAdapter(Context context, OnSongClickListener listener) {
        super(context, listener);
        this.localFileDetector = new LocalFileDetector(context);
        setShowCoverArt(false);
        // 已下载列表右侧不显示任何下载相关图标
        setShowDownloadStatus(false);
        setShowCacheStatus(false);
    }

    public void submitSongs(List<Song> songs) {
        processAndSubmitListKeepOrder(songs);
    }

    public SongWithIndex getSongItemAt(int position) {
        return super.getSongItemAt(position);
    }

    public void enterSelectionMode() {
        setSelectionMode(true);
    }

    public void exitSelectionMode() {
        setSelectionMode(false);
    }

    @Override
    public void onBindViewHolder(@NonNull SongAdapter.ViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);
        // 双重保险：在“已下载”分区强制隐藏下载相关控件，避免任何外部状态变更导致误显
        try {
            if (holder.downloadContainer != null) holder.downloadContainer.setVisibility(View.GONE);
            if (holder.btnDownload != null) holder.btnDownload.setVisibility(View.GONE);
            if (holder.downloadProgress != null) holder.downloadProgress.setVisibility(View.GONE);
            if (holder.downloadPercent != null) holder.downloadPercent.setVisibility(View.GONE);
            if (holder.downloadComplete != null) holder.downloadComplete.setVisibility(View.GONE);
            if (holder.downloadFailed != null) holder.downloadFailed.setVisibility(View.GONE);
        } catch (Exception ignore) {}

        // 显示文件大小：复用时长 TextView，展示为 "时长 · 大小"
        try {
            com.watch.limusic.model.SongWithIndex swi = getSongItemAt(position);
            if (swi != null) {
                com.watch.limusic.model.Song song = swi.getSong();
                if (song != null && song.getId() != null) {
                    long sizeBytes = localFileDetector.getDownloadedSongSize(song.getId());
                    if (sizeBytes > 0 && holder.songDuration != null) {
                        CharSequence base = holder.songDuration.getText();
                        String sizeText = formatSize(sizeBytes);
                        holder.songDuration.setText(base + " · " + sizeText);
                    }
                }
            }
        } catch (Exception ignore) {}
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0fKB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1fMB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.getDefault(), "%.2fGB", gb);
    }
} 