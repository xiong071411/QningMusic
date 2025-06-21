package com.watch.limusic.util;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

public abstract class SwipeGestureListener implements View.OnTouchListener {
    private final GestureDetector gestureDetector;
    
    public SwipeGestureListener(Context context) {
        gestureDetector = new GestureDetector(context, new GestureListener());
    }
    
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return gestureDetector.onTouchEvent(event);
    }
    
    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;
        
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }
        
        @Override
        public void onLongPress(MotionEvent e) {
            SwipeGestureListener.this.onLongPress();
        }
        
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            try {
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();
                
                if (Math.abs(diffX) > Math.abs(diffY) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD &&
                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        SwipeGestureListener.this.onSwipeRight();
                    } else {
                        SwipeGestureListener.this.onSwipeLeft();
                    }
                    return true;
                }
            } catch (Exception e) {
                // 忽略异常
            }
            return false;
        }
    }
    
    public abstract void onSwipeRight();
    public abstract void onSwipeLeft();
    public abstract void onLongPress();
} 