package com.geehe.wfbngrtl8812;


import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class WfbNgLink implements WfbNGStatsChanged{
    // Load the 'Rtl8812au_Wfbng' library on application startup.
    static {
        System.loadLibrary("WfbngRtl8812");
    }

    public static String TAG = "com.geehe.fpvue";

    private final long nativeWfbngLink;

    private UsbManager usbManager;
    private List<UsbDevice> usbDevices;
    private List<Integer> usbDeviceFileDescriptors = new ArrayList<>();
    private Timer timer;
    private WfbNGStatsChanged statsChanged;
    private final Context context;

    public WfbNgLink(final AppCompatActivity parent, UsbManager usbManager, List<UsbDevice> usbDevices) {
        this.context=parent;
        this.usbManager = usbManager;
        this.usbDevices = usbDevices;
        for (UsbDevice usbDevice : usbDevices) {
            UsbDeviceConnection usbDeviceConnection = usbManager.openDevice(usbDevice);
            usbDeviceFileDescriptors.add(usbDeviceConnection.getFileDescriptor());
        }
        nativeWfbngLink = nativeInitialize(context, usbDeviceFileDescriptors);
    }

    public void Run(int wifiChannel) {
        for (UsbDevice usbDevice : usbDevices) {
            Log.d(TAG, "wfb-ng monitoring on " + usbDevice.getDeviceName() + " using wifi channel " + wifiChannel);
        }
        timer=new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                nativeCallBack(WfbNgLink.this, nativeWfbngLink);
            }
        },0,500);
        nativeRun(nativeWfbngLink, context, wifiChannel);
        Log.d(TAG, "wfb-ng done");
    }

    public void Stop() {
        timer.cancel();
        timer.purge();
        nativeStop(nativeWfbngLink, context);
    }

    public void SetWfbNGStatsChanged(final WfbNGStatsChanged callback){
        statsChanged=callback;
    }

    // called by native code via NDK
    @Override
    public void onWfbNgStatsChanged (WfbNGStats stats) {
        if(statsChanged !=null){
            statsChanged.onWfbNgStatsChanged(stats);
        }
    }

    // Native cpp methods.
    public static native long nativeInitialize(Context context, List<Integer> fd);
    public static native void nativeRun(long nativeInstance, Context context, int wifiChannel);
    public static native void nativeStop(long nativeInstance, Context context);
    public static native <T extends WfbNGStatsChanged> void nativeCallBack(T t, long nativeInstance);
}