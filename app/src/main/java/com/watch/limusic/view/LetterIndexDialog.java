package com.watch.limusic.view;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;

import androidx.annotation.NonNull;

import com.watch.limusic.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 字母索引弹窗
 */
public class LetterIndexDialog extends Dialog {
    
    private static final String TAG = "LetterIndexDialog";
    
    // 简化字母列表，把数字和特殊字符合并为#
    private static final List<String> DEFAULT_LETTERS = Arrays.asList(
            "#", "A", "B", "C", "D", "E", "F", "G", 
            "H", "I", "J", "K", "L", "M", "N", "O", 
            "P", "Q", "R", "S", "T", "U", "V", "W", 
            "X", "Y", "Z"
    );
    
    private List<String> allLetters;
    private Set<String> availableLetters;
    private OnLetterSelectedListener listener;
    
    // 固定每行显示的列数
    private static final int COLUMNS_PER_ROW = 3;
    
    public interface OnLetterSelectedListener {
        void onLetterSelected(String letter);
    }
    
    public LetterIndexDialog(@NonNull Context context) {
        super(context, R.style.FullScreenDialog);
        this.allLetters = DEFAULT_LETTERS;
        this.availableLetters = new HashSet<>();
    }
    
    public LetterIndexDialog(@NonNull Context context, List<String> availableLetters) {
        super(context, R.style.FullScreenDialog);
        this.allLetters = DEFAULT_LETTERS;
        
        // 处理可用字母列表，将0-9的数字映射到#
        this.availableLetters = new HashSet<>();
        boolean hasDigitOrSpecial = false;
        
        for (String letter : availableLetters) {
            if (letter.equals("#") || Character.isDigit(letter.charAt(0))) {
                hasDigitOrSpecial = true;
            } else {
                this.availableLetters.add(letter);
            }
        }
        
        // 如果有任何数字或特殊字符，则添加#
        if (hasDigitOrSpecial) {
            this.availableLetters.add("#");
        }
    }
    
    public void setOnLetterSelectedListener(OnLetterSelectedListener listener) {
        this.listener = listener;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.layout_letter_index);
        
        // 设置全屏
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);
        }
        
        initLetterGrid();
        
        // 点击空白区域关闭对话框
        findViewById(android.R.id.content).setOnClickListener(v -> dismiss());
    }
    
    private void initLetterGrid() {
        LinearLayout letterContainer = findViewById(R.id.letter_container);
        letterContainer.removeAllViews();
        
        // 计算需要的行数
        int totalLetters = allLetters.size();
        int rowCount = (int) Math.ceil((double) totalLetters / COLUMNS_PER_ROW);
        
        Log.d(TAG, "总字母数: " + totalLetters + ", 每行列数: " + COLUMNS_PER_ROW + ", 总行数: " + rowCount);
        
        // 逐行创建字母按钮
        for (int i = 0; i < rowCount; i++) {
            // 创建一个水平方向的LinearLayout作为行容器
            LinearLayout rowLayout = new LinearLayout(getContext());
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(android.view.Gravity.CENTER);
            // 设置行的内边距，让字母之间有更多间隔
            rowLayout.setPadding(0, 5, 0, 5);
            
            // 计算当前行的起始和结束索引
            int startIndex = i * COLUMNS_PER_ROW;
            int endIndex = Math.min(startIndex + COLUMNS_PER_ROW, totalLetters);
            
            Log.d(TAG, "行 " + i + " 范围: " + startIndex + " 到 " + (endIndex - 1));
            
            // 为当前行添加字母按钮
            for (int j = startIndex; j < endIndex; j++) {
                String letter = allLetters.get(j);
                View letterView = LayoutInflater.from(getContext()).inflate(
                        R.layout.item_letter_grid, rowLayout, false);
                
                TextView letterText = letterView.findViewById(R.id.letter_text);
                letterText.setText(letter);
                
                // 根据是否有对应歌曲设置不同的颜色
                boolean isAvailable = availableLetters.contains(letter);
                letterText.setAlpha(isAvailable ? 1.0f : 0.5f);
                
                // 只为有效字母添加点击事件
                if (isAvailable) {
                    letterView.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onLetterSelected(letter);
                        }
                        dismiss();
                    });
                }
                
                // 设置布局权重，使按钮均匀分布
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                params.setMargins(10, 5, 10, 5); // 添加更多边距
                letterView.setLayoutParams(params);
                
                rowLayout.addView(letterView);
            }
            
            // 如果当前行的按钮数少于每行的最大列数，添加占位视图保持对齐
            for (int j = endIndex - startIndex; j < COLUMNS_PER_ROW; j++) {
                View spacerView = new View(getContext());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                spacerView.setLayoutParams(params);
                rowLayout.addView(spacerView);
            }
            
            letterContainer.addView(rowLayout);
            
            // 添加行间距
            if (i < rowCount - 1) {
                View spacer = new View(getContext());
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(12)));
                letterContainer.addView(spacer);
            }
        }
    }
    
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
} 