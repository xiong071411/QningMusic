package com.watch.limusic.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 歌单实体（Navidrome 专用）。
 * 要求：名称可重名；支持公开/私有；支持同步标记与软删除。
 */
@Entity(tableName = "playlists",
        indices = {
                @Index(value = {"name"}),
                @Index(value = {"serverId"})
        })
public class PlaylistEntity {
    @PrimaryKey(autoGenerate = true)
    private long localId;

    private String serverId; // 服务端ID（可空，离线创建时为空）

    @NonNull
    private String name;

    private boolean isPublic;

    private int songCount;

    private long createdAt;

    private long changedAt; // 服务端变更时间或本地最后变更时间

    private boolean syncDirty; // 是否存在未同步到服务器的变更

    private boolean isDeleted; // 软删除标记（用于离线补偿）

    // 新增：所有者（用于区分歌单归属显示）
    private String owner;

    public PlaylistEntity(@NonNull String name, boolean isPublic) {
        this.name = name;
        this.isPublic = isPublic;
        this.songCount = 0;
        this.createdAt = System.currentTimeMillis();
        this.changedAt = this.createdAt;
        this.syncDirty = false;
        this.isDeleted = false;
        this.owner = "";
    }

    public long getLocalId() { return localId; }
    public void setLocalId(long localId) { this.localId = localId; }

    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }

    @NonNull
    public String getName() { return name; }
    public void setName(@NonNull String name) { this.name = name; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean aPublic) { isPublic = aPublic; }

    public int getSongCount() { return songCount; }
    public void setSongCount(int songCount) { this.songCount = songCount; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getChangedAt() { return changedAt; }
    public void setChangedAt(long changedAt) { this.changedAt = changedAt; }

    public boolean isSyncDirty() { return syncDirty; }
    public void setSyncDirty(boolean syncDirty) { this.syncDirty = syncDirty; }

    public boolean isDeleted() { return isDeleted; }
    public void setDeleted(boolean deleted) { isDeleted = deleted; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
} 