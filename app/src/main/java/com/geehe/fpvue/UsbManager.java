package com.geehe.fpvue;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.util.Log;
import android.view.View;

import com.geehe.fpvue.databinding.ActivityVideoBinding;
import com.geehe.wfbngrtl8812.WfbNgLink;

import java.util.List;
import java.util.Vector;

public class UsbManager extends BroadcastReceiver {
    public static final String ACTION_USB_PERMISSION = "com.geehe.fpvue.USB_PERMISSION";
    private static final String TAG = "UsbManager";

    private final WfbNgLink wfbLink;
    private final ActivityVideoBinding binding;
    private final Context context;
    static List<UsbDevice> activeWifiAdapters = new Vector<UsbDevice>();
    boolean permissionHandled = false;

    public UsbManager(Context context, ActivityVideoBinding binding, WfbNgLink wfbNgLink )
    {
        this.binding = binding;
        this.context = context;
        this.wfbLink = wfbNgLink;
        //this.pendingIntent = pendingIntent;
    }

    @Override
    public synchronized void onReceive(Context context, Intent intent) {
        if (android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText("Wifi adapter detached.");
            UsbDevice detachedDevice = intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);
            if (detachedDevice == null ){
                return;
            }
            Log.d(TAG, "usb event detached: " + detachedDevice.getVendorId() + "/" + detachedDevice.getProductId());
            activeWifiAdapters.removeIf(usbDevice -> usbDevice.getDeviceName().equals(detachedDevice.getDeviceName()));
            try {
                wfbLink.stop(detachedDevice);
            } catch (InterruptedException ignored) {
            }
        } else if (android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice dev = intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);
            if (dev == null ){
                return;
            }
            Log.d(TAG, "usb event attached: " + dev.getVendorId() + "/" + dev.getProductId());
            startAdapter(dev, VideoActivity.getChannel(context));
            activeWifiAdapters.add(dev);
        } else if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
            permissionHandled = true;
        }
    }

    public synchronized void initWifiAdapters() {
        activeWifiAdapters.clear();
        android.hardware.usb.UsbManager manager = (android.hardware.usb.UsbManager) context.getSystemService(Context.USB_SERVICE);

        List<UsbDeviceFilter> filters;
        try {
           filters = DeviceFilterXmlParser.parseXml(context, R.xml.usb_device_filter);
        } catch (Exception e) {
           e.printStackTrace();
           return;
        }
        Log.d(TAG, "initWifiAdapters filters: " + filters);
        Log.d(TAG, "initWifiAdapters devices: " + manager.getDeviceList().values());

        for (UsbDevice dev :  manager.getDeviceList().values()) {
            boolean allowed = false;
            for (UsbDeviceFilter filter : filters) {
                if (filter.productId != dev.getProductId() || filter.venderId != dev.getVendorId()) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                continue;
            }
            Log.d(TAG, "Using device " + dev.getVendorId() + "/" + dev.getProductId());
            activeWifiAdapters.add(dev);
        }

        if (activeWifiAdapters.isEmpty()) {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText( "No compatible wifi adapter found.");
        }
    }

    public synchronized void startAdapters(int wifiChannel) {
        if (wfbLink.isRunning()) {
            return;
        }
        binding.tvMessage.setVisibility(View.VISIBLE);
        for (UsbDevice dev : activeWifiAdapters) {
            if(!startAdapter(dev, wifiChannel)){
                break;
            };
        }
    }

    public synchronized boolean startAdapter(UsbDevice dev, int wifiChannel) {
        android.hardware.usb.UsbManager usbManager = (android.hardware.usb.UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (!usbManager.hasPermission(dev)) {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText( "No permission for wifi adapter(s) " + dev.getDeviceName());
            if (!permissionHandled) {
                PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, new Intent(UsbManager.ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                usbManager.requestPermission(dev, pendingIntent);
            }
            return false;
        }
        String name = dev.getDeviceName().split("/dev/bus/")[1];
        if (binding.tvMessage.getText().toString().startsWith("Starting") && !binding.tvMessage.getText().toString().endsWith(name)) {
            binding.tvMessage.setText(binding.tvMessage.getText()+ ", " + name);
        } else {
            binding.tvMessage.setText( "Starting wfb-ng on channel " + wifiChannel + " with " + name);
        }
        wfbLink.Run(wifiChannel, dev);
        return true;
    }

    public synchronized void stopAdapters() {
        try {
            wfbLink.stopAll();
        } catch (InterruptedException ignored) {
        }
    }
}
