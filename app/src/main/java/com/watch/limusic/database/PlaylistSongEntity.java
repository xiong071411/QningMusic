package com.watch.limusic.database;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.annotation.NonNull;

/**
 * 歌单-歌曲 明细表，维护歌单内的顺序（ordinal）。
 * 允许同一 songId 多次添加，主键采用 (playlistLocalId, ordinal)。
 */
@Entity(tableName = "playlist_songs",
        primaryKeys = {"playlistLocalId", "ordinal"},
        indices = {
                @Index(value = {"playlistLocalId"}),
                @Index(value = {"songId"}),
                @Index(value = {"playlistLocalId", "songId"})
        },
        foreignKeys = {
                @ForeignKey(entity = PlaylistEntity.class,
                        parentColumns = "localId",
                        childColumns = "playlistLocalId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(entity = SongEntity.class,
                        parentColumns = "id",
                        childColumns = "songId",
                        onDelete = ForeignKey.NO_ACTION)
        }
)
public class PlaylistSongEntity {
    private long playlistLocalId;
    @NonNull
    private String songId;
    private int ordinal; // 歌单内顺序（0 为最上）
    private long addedAt; // 添加时间

    public PlaylistSongEntity(long playlistLocalId, @NonNull String songId, int ordinal, long addedAt) {
        this.playlistLocalId = playlistLocalId;
        this.songId = songId;
        this.ordinal = ordinal;
        this.addedAt = addedAt;
    }

    public long getPlaylistLocalId() { return playlistLocalId; }
    public void setPlaylistLocalId(long playlistLocalId) { this.playlistLocalId = playlistLocalId; }

    @NonNull
    public String getSongId() { return songId; }
    public void setSongId(@NonNull String songId) { this.songId = songId; }

    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }

    public long getAddedAt() { return addedAt; }
    public void setAddedAt(long addedAt) { this.addedAt = addedAt; }
} 