package com.geehe.fpvue.osd;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

public class MovableLayout extends LinearLayout {
    private float dX, dY;
    private SharedPreferences preferences;
    private boolean isMovable = false;

    public MovableLayout(Context context) {
        super(context);
        init(context);
    }

    public MovableLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MovableLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        preferences = context.getSharedPreferences("movable_layout_prefs", Context.MODE_PRIVATE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(!isMovable) return false;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dX = this.getX() - event.getRawX();
                dY = this.getY() - event.getRawY();
                break;
            case MotionEvent.ACTION_MOVE:
                this.animate()
                        .x(event.getRawX() + dX)
                        .y(event.getRawY() + dY)
                        .setDuration(0)
                        .start();
                break;
            case MotionEvent.ACTION_UP:
                savePosition();
                break;
            default:
                return false;
        }
        return true;
    }

    private void savePosition() {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat(getId() + "_x", getX());
        editor.putFloat(getId() + "_y", getY());
        editor.apply();
    }

    public void restorePosition() {
        float x = preferences.getFloat(getId() + "_x", 0);
        float y = preferences.getFloat(getId() + "_y", 0);
        setX(x);
        setY(y);
    }

    public void setMovable(boolean movable) {
        isMovable = movable;
    }
}
