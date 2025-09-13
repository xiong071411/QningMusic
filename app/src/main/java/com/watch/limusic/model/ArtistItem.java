package com.watch.limusic.model;

/**
 * 用于艺术家列表UI渲染的条目
 */
public class ArtistItem {
	private final String name;
	private final int songCount;
	private final String sortLetter;

	public ArtistItem(String name, int songCount, String sortLetter) {
		this.name = name != null && !name.trim().isEmpty() ? name.trim() : "(未知艺术家)";
		this.songCount = Math.max(0, songCount);
		this.sortLetter = sortLetter != null ? sortLetter : "#";
	}

	public String getName() { return name; }
	public int getSongCount() { return songCount; }
	public String getSortLetter() { return sortLetter; }

	public long getStableId() {
		String key = name != null ? name.trim().toLowerCase() : "(unknown)";
		return key.hashCode();
	}
} 