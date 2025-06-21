package com.watch.limusic.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

/**
 * 拼音工具类，用于提取中文首字母，支持中英文混合
 */
public class PinyinUtil {
    
    /**
     * 获取字符串的首字母（大写）
     * 如果是中文，则转换为拼音首字母
     * 如果是英文，则返回首字母
     * 如果是数字，则返回数字
     * 如果是特殊字符，则返回#
     * @param str 输入字符串
     * @return 首字母（大写）或#
     */
    public static String getFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return "#";
        }
        
        // 清除字符串前面的空白字符
        str = str.trim();
        if (str.isEmpty()) {
            return "#";
        }
        
        // 取第一个字符
        char firstChar = str.charAt(0);
        
        // 如果是数字或字母，直接返回
        if (isEnglishLetter(firstChar)) {
            return String.valueOf(Character.toUpperCase(firstChar));
        } else if (Character.isDigit(firstChar)) {
            return String.valueOf(firstChar);
        } else if (isChinese(firstChar)) {
            // 中文字符，转换为拼音
            return getPinyinFirstLetter(firstChar);
        } else {
            // 特殊字符
            return "#";
        }
    }
    
    /**
     * 判断是否为英文字母
     */
    private static boolean isEnglishLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }
    
    /**
     * 判断是否为中文字符
     */
    private static boolean isChinese(char ch) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(ch);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }
    
    /**
     * 获取中文字符的拼音首字母
     */
    private static String getPinyinFirstLetter(char ch) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.UPPERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        
        try {
            String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(ch, format);
            if (pinyins != null && pinyins.length > 0) {
                return String.valueOf(pinyins[0].charAt(0));
            } else {
                return "#";
            }
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            return "#";
        }
    }
} 