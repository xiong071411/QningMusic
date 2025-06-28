package com.watch.limusic.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;
import com.watch.limusic.model.Album;
import com.watch.limusic.util.ImageLoader;

import java.util.Objects;

public class AlbumAdapter extends ListAdapter<Album, AlbumAdapter.ViewHolder> {
    private final Context context;
    private OnAlbumClickListener listener;

    public interface OnAlbumClickListener {
        void onAlbumClick(Album album);
    }

    public AlbumAdapter(Context context) {
        super(DIFF_CALLBACK);
        this.context = context;
    }

    public void setOnAlbumClickListener(OnAlbumClickListener listener) {
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<Album> DIFF_CALLBACK = new DiffUtil.ItemCallback<Album>() {
        @Override
        public boolean areItemsTheSame(@NonNull Album oldItem, @NonNull Album newItem) {
            return Objects.equals(oldItem.getId(), newItem.getId());
        }

        @Override
        public boolean areContentsTheSame(@NonNull Album oldItem, @NonNull Album newItem) {
            return Objects.equals(oldItem.getName(), newItem.getName()) &&
                   Objects.equals(oldItem.getDisplayArtist(), newItem.getDisplayArtist()) &&
                   oldItem.getSongCount() == newItem.getSongCount();
        }
    };

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_album, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Album album = getItem(position);
        holder.albumName.setText(album.getName());
        holder.albumArtist.setText(album.getDisplayArtist());
        holder.albumInfo.setText(String.format("%d首歌曲 • %d年",
                album.getSongCount(), album.getYear()));

        // 使用ImageLoader加载专辑封面
        ImageLoader.loadAlbumListCover(context, album.getCoverArt(), holder.albumCover);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAlbumClick(album);
            }
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView albumCover;
        final TextView albumName;
        final TextView albumArtist;
        final TextView albumInfo;

        ViewHolder(View view) {
            super(view);
            albumCover = view.findViewById(R.id.album_cover);
            albumName = view.findViewById(R.id.album_name);
            albumArtist = view.findViewById(R.id.album_artist);
            albumInfo = view.findViewById(R.id.album_info);
        }
    }
} 