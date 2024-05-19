package com.geehe.fpvue.osd;

import android.content.Context;

public class OSDElement{
    public String name;
    public MovableLayout layout;

    public OSDElement(String n, MovableLayout l) {
        name = n;
        layout = l;
    }
}
