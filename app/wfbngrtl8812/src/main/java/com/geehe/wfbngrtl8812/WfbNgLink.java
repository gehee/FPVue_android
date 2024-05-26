package com.geehe.wfbngrtl8812;


import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class WfbNgLink implements WfbNGStatsChanged{
    // Load the 'Rtl8812au_Wfbng' library on application startup.
    static {
        System.loadLibrary("WfbngRtl8812");
    }

    public static String TAG = "com.geehe.fpvue";

    private final long nativeWfbngLink;
    private final Timer timer;
    private WfbNGStatsChanged statsChanged;
    private final Context context;
    Map<UsbDevice, Thread> linkThreads = new HashMap<UsbDevice, Thread>();
    Map<UsbDevice, UsbDeviceConnection> linkConns = new HashMap<UsbDevice, UsbDeviceConnection>();

    public WfbNgLink(final AppCompatActivity parent) {
        this.context=parent;
        nativeWfbngLink = nativeInitialize(context);
        timer=new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                nativeCallBack(WfbNgLink.this, nativeWfbngLink);
            }
        },0,500);
    }

    public boolean isRunning() {
        return !linkThreads.isEmpty();
    }

    public synchronized void Run(int wifiChannel, UsbDevice usbDevice) {
        Log.d(TAG, "wfb-ng monitoring on " + usbDevice.getDeviceName() + " using wifi channel " + wifiChannel);
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection usbDeviceConnection = usbManager.openDevice(usbDevice);
        int fd = usbDeviceConnection.getFileDescriptor();
        linkThreads.put(usbDevice, new Thread(() -> nativeRun(nativeWfbngLink, context, wifiChannel, fd)));
        linkConns.put(usbDevice, usbDeviceConnection);
        linkThreads.get(usbDevice).start();
        Log.d(TAG, "wfb-ng on "+ usbDevice.getDeviceName()+ " done.");
    }

    public synchronized void stopAll() throws InterruptedException {
        for (Map.Entry<UsbDevice, UsbDeviceConnection> entry : linkConns.entrySet()) {
            nativeStop(nativeWfbngLink, context, entry.getValue().getFileDescriptor());
        }
        for (Map.Entry<UsbDevice, UsbDeviceConnection> entry : linkConns.entrySet()) {
            linkThreads.get(entry.getKey()).join();
        }
        linkThreads.clear();
    }

    public synchronized void stop(UsbDevice dev) throws InterruptedException {
        UsbDeviceConnection conn = linkConns.get(dev);
        if (conn == null) {
            return;
        }
        int fd = conn.getFileDescriptor();
        nativeStop(nativeWfbngLink, context, fd);
        linkThreads.get(dev).join();
        linkThreads.remove(dev);
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
    public static native long nativeInitialize(Context context);
    public static native void nativeRun(long nativeInstance, Context context, int wifiChannel, int fd);
    public static native void nativeStop(long nativeInstance, Context context, int fd);
    public static native <T extends WfbNGStatsChanged> void nativeCallBack(T t, long nativeInstance);
}