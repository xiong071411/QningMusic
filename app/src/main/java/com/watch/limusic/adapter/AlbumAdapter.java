package com.watch.limusic.adapter;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.watch.limusic.R;
import com.watch.limusic.model.Album;
import com.watch.limusic.api.NavidromeApi;
import com.watch.limusic.download.LocalFileDetector;

import java.util.ArrayList;
import java.util.List;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.ViewHolder> {
    private final Context context;
    private final List<Album> albums;
    private OnAlbumClickListener listener;

    public interface OnAlbumClickListener {
        void onAlbumClick(Album album);
    }

    public AlbumAdapter(Context context) {
        this.context = context;
        this.albums = new ArrayList<>();
    }

    public void setOnAlbumClickListener(OnAlbumClickListener listener) {
        this.listener = listener;
    }
    
    // 设置新的专辑列表（完全替换）
    public void setAlbums(List<Album> newAlbums) {
        this.albums.clear();
        if (newAlbums != null) {
            this.albums.addAll(newAlbums);
        }
        notifyDataSetChanged();
    }
    
    // 添加更多专辑（增量添加）
    public void addAlbums(List<Album> moreAlbums) {
        if (moreAlbums != null && !moreAlbums.isEmpty()) {
            int startPosition = this.albums.size();
            this.albums.addAll(moreAlbums);
            // 只通知新添加的项目，提高性能
            notifyItemRangeInserted(startPosition, moreAlbums.size());
        }
    }
    
    // 清空专辑列表
    public void clearAlbums() {
        int size = this.albums.size();
        this.albums.clear();
        notifyItemRangeRemoved(0, size);
    }
    
    // 获取当前专辑列表
    public List<Album> getAlbums() {
        return new ArrayList<>(albums);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_album, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Album album = albums.get(position);
        holder.bind(album);
    }

    @Override
    public int getItemCount() {
        return albums.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView albumCoverView;
        private final TextView albumTitleView;
        private final TextView albumArtistView;
        private final TextView albumInfoView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            albumCoverView = itemView.findViewById(R.id.album_cover);
            albumTitleView = itemView.findViewById(R.id.album_name);
            albumArtistView = itemView.findViewById(R.id.album_artist);
            albumInfoView = itemView.findViewById(R.id.album_info);
            
            // 设置整个项的点击事件
            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    Album album = albums.get(position);
                    Log.d("AlbumAdapter", "专辑点击: " + album.getName() + ", ID: " + album.getId() + ", 位置: " + position);
                    
                    // 确保专辑ID不为空
                    if (album.getId() == null || album.getId().isEmpty()) {
                        Log.e("AlbumAdapter", "专辑ID为空，无法加载歌曲");
                        return;
                    }
                    
                    // 调用监听器
                    listener.onAlbumClick(album);
                } else {
                    Log.e("AlbumAdapter", "专辑点击无效: 位置=" + position + ", 监听器=" + (listener != null ? "已设置" : "未设置"));
                }
            });
        }

        public void bind(Album album) {
            albumTitleView.setText(album.getName());
            albumArtistView.setText(album.getArtist());
            
            // 设置专辑信息：年份和歌曲数
            String infoText = album.getSongCount() + "首歌曲";
            if (album.getYear() > 0) {
                infoText += " • " + album.getYear() + "年";
            }
            albumInfoView.setText(infoText);
            
            // 使用Glide加载图片：优先本地封面，其次网络URL（为网络URL添加稳定签名）
            String albumId = album.getId();
            String key = (album.getCoverArt() != null && !album.getCoverArt().isEmpty()) ? album.getCoverArt() : albumId;
            String localCover = null;
            if (albumId != null && !albumId.isEmpty()) {
                localCover = new LocalFileDetector(context).getDownloadedAlbumCoverPath(albumId);
            }
            String coverArtUrl = (localCover != null) ? ("file://" + localCover) : NavidromeApi.getInstance(context).getCoverArtUrl(key);
            boolean isLocal = coverArtUrl != null && coverArtUrl.startsWith("file://");

            if (isLocal) {
                Glide.with(context)
                    .load(coverArtUrl)
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .override(100, 100)
                    .into(albumCoverView);
            } else {
            Glide.with(context)
                .load(coverArtUrl)
                .placeholder(R.drawable.default_album_art)
                .error(R.drawable.default_album_art)
                    .signature(new ObjectKey(key != null ? key : ""))
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .override(100, 100)
                .into(albumCoverView);
            }
        }
    }
} 