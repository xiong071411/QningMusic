package com.watch.limusic.database;

/**
 * 承接基于 songs 表的艺术家聚合统计结果
 */
public class ArtistCount {
	public String name;
	public int songCount;

	public ArtistCount() {}

	public ArtistCount(String name, int songCount) {
		this.name = name;
		this.songCount = songCount;
	}

	public String getName() { return name; }
	public int getSongCount() { return songCount; }
} 