package com.geehe.fpvue;

public class UsbDeviceFilter {
    public int venderId;
    public int productId;

    public UsbDeviceFilter(int vid, int pid) {
        venderId = vid;
        productId = pid;
    }
}
