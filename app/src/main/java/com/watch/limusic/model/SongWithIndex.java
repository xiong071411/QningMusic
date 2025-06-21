package com.watch.limusic.model;

import com.watch.limusic.util.PinyinUtil;

import java.util.Objects;

/**
 * 带有排序索引信息的歌曲封装类
 */
public class SongWithIndex implements Comparable<SongWithIndex> {
    private final Song song;  // 原始歌曲对象
    private int position; // 排序后的位置
    private final String sortLetter; // 排序用的首字母
    private String displayNumber; // 显示的编号，如 "01"
    
    public SongWithIndex(Song song, int position) {
        this.song = song;
        this.position = position;
        
        // 提取标题的首字母用于排序
        if (song != null && song.getTitle() != null) {
            this.sortLetter = PinyinUtil.getFirstLetter(song.getTitle());
        } else {
            this.sortLetter = "#";
        }
    }

    public String getId() {
        return song.getId();
    }
    
    public Song getSong() {
        return song;
    }
    
    public int getPosition() {
        return position;
    }
    
    public void setPosition(int position) {
        this.position = position;
        updateDisplayNumber();
    }
    
    public String getSortLetter() {
        return sortLetter;
    }
    
    public String getDisplayNumber() {
        if (displayNumber == null) {
            updateDisplayNumber();
        }
        return displayNumber;
    }
    
    private void updateDisplayNumber() {
        // 生成适合显示的编号（前导零）
        this.displayNumber = String.format("%02d", position + 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SongWithIndex that = (SongWithIndex) o;
        return position == that.position &&
               Objects.equals(song, that.song) &&
               Objects.equals(sortLetter, that.sortLetter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(song, position, sortLetter);
    }

    @Override
    public int compareTo(SongWithIndex other) {
        // 首先按字母分组
        int letterCompare = this.sortLetter.compareTo(other.sortLetter);
        if (letterCompare != 0) {
            // #排最前
            if ("#".equals(this.sortLetter)) return -1;
            if ("#".equals(other.sortLetter)) return 1;

            // 数字排在字母前
            boolean thisIsDigit = Character.isDigit(this.sortLetter.charAt(0));
            boolean otherIsDigit = Character.isDigit(other.sortLetter.charAt(0));

            if (thisIsDigit && !otherIsDigit) return -1;
            if (!thisIsDigit && otherIsDigit) return 1;
            
            return letterCompare;
        }
        
        // 相同字母下，按标题排序
        if (this.song != null && this.song.getTitle() != null && other.song != null && other.song.getTitle() != null) {
            return this.song.getTitle().compareTo(other.song.getTitle());
        }
        
        return 0;
    }
} 