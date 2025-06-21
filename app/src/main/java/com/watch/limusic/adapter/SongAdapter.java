package com.watch.limusic.adapter;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.watch.limusic.R;
import com.watch.limusic.model.Song;
import com.watch.limusic.model.SongWithIndex;
import com.watch.limusic.api.NavidromeApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SongAdapter extends ListAdapter<SongWithIndex, SongAdapter.ViewHolder> {
    private final Context context;
    private final OnSongClickListener listener;
    private boolean showCoverArt = true;

    // 字母索引映射，存储每个字母在列表中的位置
    private final Map<String, Integer> letterPositionMap = new HashMap<>();

    public interface OnSongClickListener {
        void onSongClick(Song song, int position);
    }

    public SongAdapter(Context context, OnSongClickListener listener) {
        super(DIFF_CALLBACK);
        this.context = context;
        this.listener = listener;
    }

    public void setShowCoverArt(boolean showCoverArt) {
        this.showCoverArt = showCoverArt;
        // ListAdapter 会处理更新，但这里我们改变的是所有 ViewHolder 的行为，
        // 所以需要一个完整的重绘。
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
            String coverArtUrl = NavidromeApi.getInstance(context).getCoverArtUrl(song.getCoverArtUrl());
            Glide.with(context)
                    .load(coverArtUrl)
                    .override(100, 100)
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                    .into(holder.albumArt);
        } else {
            holder.albumArt.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSongClick(song, holder.getAdapterPosition());
            }
        });
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

    // 新增方法，用于处理从 MainActivity 传递过来的原始歌曲列表
    public void processAndSubmitList(List<Song> songs) {
        // 在后台线程处理数据转换和排序
        new Thread(() -> {
            // 1. 转换并排序
            ArrayList<SongWithIndex> songItems = new ArrayList<>();
            for (int i = 0; i < songs.size(); i++) {
                songItems.add(new SongWithIndex(songs.get(i), i));
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

        ViewHolder(View itemView) {
            super(itemView);
            albumArt = itemView.findViewById(R.id.album_art);
            songTitle = itemView.findViewById(R.id.song_title);
            songArtist = itemView.findViewById(R.id.song_artist);
            songDuration = itemView.findViewById(R.id.song_duration);
            songNumber = itemView.findViewById(R.id.song_number);
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
}