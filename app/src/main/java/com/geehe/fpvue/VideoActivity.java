package com.geehe.fpvue;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.geehe.fpvue.databinding.ActivityVideoBinding;
import com.geehe.fpvue.osd.OSDElement;
import com.geehe.mavlink.MavlinkData;
import com.geehe.mavlink.MavlinkNative;
import com.geehe.mavlink.MavlinkUpdate;
import com.geehe.videonative.DecodingInfo;
import com.geehe.videonative.IVideoParamsChanged;
import com.geehe.videonative.VideoPlayer;
import com.geehe.wfbngrtl8812.WfbNGStats;
import com.geehe.wfbngrtl8812.WfbNGStatsChanged;
import com.geehe.wfbngrtl8812.WfbNgLink;
import com.geehe.fpvue.osd.OSDManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import me.saket.cascade.CascadePopupMenuCheckable;


// Most basic implementation of an activity that uses VideoNative to stream a video
// Into an Android Surface View
public class VideoActivity extends AppCompatActivity implements IVideoParamsChanged, WfbNGStatsChanged, MavlinkUpdate, SettingsChanged {
    private ActivityVideoBinding binding;
    protected DecodingInfo mDecodingInfo;
    int lastVideoW=0,lastVideoH=0;
    private OSDManager osdManager;

    private static final String TAG = "com.geehe.fpvue";
    private static final String ACTION_USB_PERMISSION = "com.geehe.fpvue.USB_PERMISSION";

    BroadcastReceiver usbReceiver;
    BroadcastReceiver batteryReceiver;
    List<UsbDevice> activeWifiAdapters = new Vector<UsbDevice>();
    WfbNgLink wfbLink;
    Thread wfbThread;
    boolean permissionRefused = false;

    VideoPlayer videoPlayerH264;
    VideoPlayer videoPlayerH265;

    private String activeCodec;
    private int activeChannel;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        binding = ActivityVideoBinding.inflate(getLayoutInflater());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Setup video player
        setContentView(binding.getRoot());
        videoPlayerH264 = new VideoPlayer(this);
        videoPlayerH264.setIVideoParamsChanged(this);
        binding.svH264.getHolder().addCallback(videoPlayerH264.configure1());

        videoPlayerH265 = new VideoPlayer(this);
        videoPlayerH265.setIVideoParamsChanged(this);
        binding.svH265.getHolder().addCallback(videoPlayerH265.configure1());

        osdManager = new OSDManager(this, binding);
        osdManager.setUp();

        SharedPreferences prefs = this.getPreferences(Context.MODE_PRIVATE);
        binding.btnSettings.setOnClickListener(v -> {
            CascadePopupMenuCheckable popup = new CascadePopupMenuCheckable(VideoActivity.this, v);

            SubMenu chnMenu = popup.getMenu().addSubMenu("Channel");
            int channelPref = prefs.getInt("wifi-channel", 11);
            chnMenu.setHeaderTitle("Current: " + channelPref);
            String[] channels = getResources().getStringArray(R.array.channels);
            for (String chnStr : channels) {
                if (channelPref==Integer.parseInt(chnStr)){
                    continue;
                }
                chnMenu.add(chnStr).setOnMenuItemClickListener(item -> {
                    onChannelSettingChanged(Integer.parseInt(chnStr));
                    return true;
                });
            }

            // Codecs
            String codecPref = prefs.getString("codec", "h265");
            SubMenu codecMenu = popup.getMenu().addSubMenu("Codec");
            codecMenu.setHeaderTitle("Current: " + codecPref);

            String[] codecs = getResources().getStringArray(R.array.codecs);
            for (String codecStr : codecs) {
                if (codecPref.equals(codecStr)){
                    continue;
                }
                codecMenu.add(codecStr).setOnMenuItemClickListener(item -> {
                    onCodecSettingChanged(codecStr);
                    return true;
                });
            }

            // OSD
            SubMenu osd = popup.getMenu().addSubMenu("OSD");
            for (OSDElement element: osdManager.listOSDItems) {
                MenuItem itm = osd.add(element.name);
                itm.setCheckable(true);
                itm.setChecked(osdManager.isElementEnabled(element.layout.getId()));
                itm.setOnMenuItemClickListener(item -> {
                    item.setChecked(!item.isChecked());
                    osdManager.onOSDItemCheckChanged(element, item.isChecked());
                    return true;
                });
            }

            String lockLabel = osdManager.isOSDLocked() ? "Unlock OSD" : "Lock OSD";
            MenuItem lock = popup.getMenu().add(lockLabel);
            lock.setOnMenuItemClickListener(item -> {
                osdManager.lockOSD(!osdManager.isOSDLocked());
                return true;
            });

            popup.show();
        });

        // Setup mavlink
        MavlinkNative.nativeStart(this);
        Timer mavlinkTimer = new Timer();
        mavlinkTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                MavlinkNative.nativeCallBack(VideoActivity.this);
            }
        },0,200);

        copyGSKey();

        usbReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) { 
                synchronized (this) {
                    if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                        Log.d(TAG, "usb detached.");
                        binding.tvMessage.setVisibility(View.VISIBLE);
                        binding.tvMessage.setText("Wifi adapter detached.");
                        UsbDevice detachedDevice = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (detachedDevice == null ){
                            return;
                        }
                        activeWifiAdapters.removeIf(usbDevice -> usbDevice.getDeviceName().equals(detachedDevice.getDeviceName()));
                        if (activeWifiAdapters.isEmpty()) {
                            StopWfbNg();
                        }
                    } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                        StopWfbNg();
                        Log.d(TAG, "usb attached.");
                    } else if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                        StopWfbNg();
                        permissionRefused = !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    }
                }
            }
        };
        batteryReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent batteryStatus) {
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                float batteryPct = level * 100 / (float)scale;
                binding.tvGSBattery.setText((int)batteryPct+"%");

                int icon = 0;
                if (isCharging) {
                    icon = R.drawable.baseline_battery_charging_full_24;
                } else {
                    if (batteryPct<=0) {
                        icon = R.drawable.baseline_battery_0_bar_24;
                    } else if (batteryPct<=1/7.0*100) {
                        icon = R.drawable.baseline_battery_1_bar_24;
                    } else if (batteryPct<=2/7.0*100) {
                        icon = R.drawable.baseline_battery_2_bar_24;
                    } else if (batteryPct<=3/7.0*100) {
                        icon = R.drawable.baseline_battery_3_bar_24;
                    } else if (batteryPct<=4/7.0*100) {
                        icon = R.drawable.baseline_battery_4_bar_24;
                    } else if (batteryPct<=5/7.0*100) {
                        icon = R.drawable.baseline_battery_5_bar_24;
                    } else if (batteryPct<=6/7.0*100) {
                        icon = R.drawable.baseline_battery_6_bar_24;
                    } else {
                        icon = R.drawable.baseline_battery_full_24;
                    }
                }
                binding.imgGSBattery.setImageResource(icon);
            }
        };
    }

    public void registerReceivers(){
        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(ACTION_USB_PERMISSION);
        IntentFilter batFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(batteryReceiver, batFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, usbFilter);
            registerReceiver(batteryReceiver, batFilter);
        }
    }

    public void unregisterReceivers() {
        try {
            unregisterReceiver(usbReceiver);
        } catch(java.lang.IllegalArgumentException ignored) {}
        try {
            unregisterReceiver(batteryReceiver);
        } catch(java.lang.IllegalArgumentException ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    protected void onStop() {
        MavlinkNative.nativeStop(this);
        Log.d(TAG, "onStop");
        StopWfbNg();
        unregisterReceivers();
        super.onStop();
    }

    protected void onResume() {
        Log.d(TAG, "onResume");
        registerReceivers();
        StartVideoPlayer();
        StartWfbNg();
        super.onResume();
        osdManager.restoreOSDConfig();
    }

    @Override
    public void onChannelSettingChanged(int channel) {
        if (activeChannel == channel) {
            return;
        }
        SharedPreferences prefs = this.getPreferences(MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("wifi-channel", channel);
        editor.apply();
        StartWfbNg();
    }

    @Override
    public void onCodecSettingChanged(String codec) {
        if (codec.equals(activeCodec)) {
            return;
        }
        SharedPreferences prefs = this.getPreferences(MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("codec", codec);
        editor.apply();
        StartVideoPlayer();
    }

    public synchronized void StartVideoPlayer() {
        videoPlayerH264.stop();
        videoPlayerH265.stop();
        SharedPreferences sharedPref = this.getPreferences(MODE_PRIVATE);
        String codec = sharedPref.getString("codec", "h265");
        if (codec.equals("h265")) {
            videoPlayerH265.start(codec);
            binding.svH264.setVisibility(View.INVISIBLE);
            binding.svH265.setVisibility(View.VISIBLE);
        } else {
            videoPlayerH264.start(codec);
            binding.svH265.setVisibility(View.INVISIBLE);
            binding.svH264.setVisibility(View.VISIBLE);
        }
        activeCodec=codec;
    }

    public synchronized void StartWfbNg(){
        StopWfbNg();

        SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        int wifiChannel = sharedPref.getInt("wifi-channel", 11);
        if (wifiChannel < 0) {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText("No channel selected.");
            return;
        }
        activeWifiAdapters.clear();
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
        for (UsbDevice usbDevice : deviceList.values()) {
            activeWifiAdapters.add(usbDevice);
            Log.d(TAG, "Found adapter" + usbDevice.getDeviceName());
        }
        if (activeWifiAdapters.isEmpty()) {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText( "No compatible wifi adapter found.");
            return ;
        }

        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        for (UsbDevice dev : activeWifiAdapters) {
            if (!usbManager.hasPermission(dev)) {
                binding.tvMessage.setVisibility(View.VISIBLE);
                binding.tvMessage.setText("No permission for wifi adapter.");
                PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                if (!permissionRefused) {
                    usbManager.requestPermission(dev, usbPermissionIntent);
                }
                return;
            }
        }

        binding.tvMessage.setVisibility(View.VISIBLE);
        binding.tvMessage.setText( "Starting wfb-ng on channel " + wifiChannel+ ".");

        wfbLink = new WfbNgLink(VideoActivity.this, usbManager, activeWifiAdapters);
        Log.d(TAG, "wfb-ng link started.");
        wfbLink.SetWfbNGStatsChanged(VideoActivity.this);
        activeChannel = wifiChannel;
        wfbThread = new Thread() {
            @Override
            public void run() {
                wfbLink.Run(wifiChannel);
                Log.d(TAG, "wfb-ng link stopped.");
            }
        };
        wfbThread.start();
    }

    public synchronized void StopWfbNg() {
        if (wfbLink == null) {
            return;
        }
        wfbLink.Stop();
        try {
            wfbThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        wfbLink = null;
    }

    @Override
    public void onVideoRatioChanged(final int videoW,final int videoH) {
        lastVideoW=videoW;
        lastVideoH=videoH;
    }

    @Override
    public void onDecodingInfoChanged(final DecodingInfo decodingInfo) {
        mDecodingInfo=decodingInfo;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                binding.tvMessage.setVisibility(View.INVISIBLE);
                if (decodingInfo.currentKiloBitsPerSecond > 1000) {
                    binding.tvVideoStats.setText(String.format("%dx%d@%.0f   %.1f Mbps   %.1f ms",lastVideoW, lastVideoH, decodingInfo.currentFPS, decodingInfo.currentKiloBitsPerSecond/1000, decodingInfo.avgTotalDecodingTime_ms));
                } else {
                    binding.tvVideoStats.setText(String.format("%dx%d@%.0f   %.1f Kpbs   %.1f ms",lastVideoW, lastVideoH, decodingInfo.currentFPS, decodingInfo.currentKiloBitsPerSecond, decodingInfo.avgTotalDecodingTime_ms));
                }
            }
        });
    }

    @Override
    public void onWfbNgStatsChanged(WfbNGStats data) {
        binding.tvMessage.setVisibility(View.INVISIBLE);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (data.count_p_all > 0) {
                    int perr = data.count_p_dec_err;
                    if (perr > 0) {
                        binding.tvLinkStatus.setText("Waiting for session key.");
                    } else {
                        binding.tvLinkStatus.setText(String.format("lost=%d\t\trec=%d\t\tok=%d",
                                data.count_p_lost,
                                data.count_p_fec_recovered ,
                                data.count_p_outgoing));
                    }
                } else {
                    binding.tvLinkStatus.setText("No wfb-ng data.");
                }
            }
        });
    }

    private String copyGSKey() {
        AssetManager assetManager = getAssets();
        File file = new File(getApplicationContext().getFilesDir(), "gs.key");
        Log.d(TAG, "Copying file to " + file.getAbsolutePath());
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assetManager.open("gs.key");
            out = new FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int read;
            while((read = in.read(buffer)) != -1){
                out.write(buffer, 0, read);
            }
        } catch(IOException e) {
            Log.e("tag", "Failed to copy asset", e);
        }
        finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // NOOP
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // NOOP
                }
            }
        }
        return file.getAbsolutePath();
    }

    @Override
    public void onNewMavlinkData(MavlinkData data) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                osdManager.render(data);
            }
        });
    }
}
