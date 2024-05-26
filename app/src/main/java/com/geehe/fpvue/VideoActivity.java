package com.geehe.fpvue;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
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
import java.util.Timer;
import java.util.TimerTask;

import me.saket.cascade.CascadePopupMenuCheckable;


// Most basic implementation of an activity that uses VideoNative to stream a video
// Into an Android Surface View
public class VideoActivity extends AppCompatActivity implements IVideoParamsChanged, WfbNGStatsChanged, MavlinkUpdate, SettingsChanged {
    private ActivityVideoBinding binding;
    protected DecodingInfo mDecodingInfo;
    int lastVideoW=0,lastVideoH=0;
    private OSDManager osdManager;

    private static final String TAG = "VideoActivity";

    UsbManager usbManager;
    BroadcastReceiver batteryReceiver;
    WfbNgLink wfbLink;

    VideoPlayer videoPlayerH264;
    VideoPlayer videoPlayerH265;
    private String activeCodec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "lifecycle onCreate " +  this);
        super.onCreate(savedInstanceState);
        binding = ActivityVideoBinding.inflate(getLayoutInflater());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Init wfb ng.
        copyGSKey();
        wfbLink = new WfbNgLink(VideoActivity.this);
        wfbLink.SetWfbNGStatsChanged(VideoActivity.this);
        usbManager = new UsbManager(this, binding, wfbLink);
        usbManager.initWifiAdapters();

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
            int channelPref = getChannel(this);
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
        usbFilter.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_PERMISSION);
        IntentFilter batFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbManager, usbFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(batteryReceiver, batFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbManager, usbFilter);
            registerReceiver(batteryReceiver, batFilter);
        }
    }

    public void unregisterReceivers() {
        try {
            unregisterReceiver(usbManager);
        } catch(java.lang.IllegalArgumentException ignored) {}
        try {
            unregisterReceiver(batteryReceiver);
        } catch(java.lang.IllegalArgumentException ignored) {}
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "lifecycle onPause " +  this);
        super.onPause();
    }

    protected void onStop() {
        Log.d(TAG, "lifecycle onStop " +  this);
        MavlinkNative.nativeStop(this);
        unregisterReceivers();
        usbManager.stopAdapters();
        super.onStop();
    }

    protected void onResume() {
        Log.d(TAG, "lifecycle onResume " +  this);
        registerReceivers();
        StartVideoPlayer();
        usbManager.startAdapters(getChannel(this));
        osdManager.restoreOSDConfig();
        super.onResume();
    }

    public static String getCodec(Context context) {
        return context.getSharedPreferences("general", Context.MODE_PRIVATE).getString("codec", "h265");
    }

    public static int getChannel(Context context) {
        return context.getSharedPreferences("general", Context.MODE_PRIVATE).getInt("wifi-channel", 149);
    }

    @Override
    public void onChannelSettingChanged(int channel) {
        int currentChannel = getChannel(this);
        if (currentChannel == channel) {
            return;
        }
        SharedPreferences prefs = this.getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("wifi-channel", channel);
        editor.apply();
        usbManager.stopAdapters();
        usbManager.startAdapters(channel);
    }

    @Override
    public void onCodecSettingChanged(String codec) {
        if (codec.equals(activeCodec)) {
            return;
        }
        SharedPreferences prefs = this.getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("codec", codec);
        editor.apply();
        StartVideoPlayer();
    }

    public synchronized void StartVideoPlayer() {
        videoPlayerH264.stop();
        videoPlayerH265.stop();
        String codec = getCodec(this);
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
                if (decodingInfo.currentFPS > 0) {
                    binding.tvMessage.setVisibility(View.INVISIBLE);
                }
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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (data.count_p_all > 0) {
                    binding.tvMessage.setVisibility(View.INVISIBLE);
                    if (data.count_p_dec_err > 0) {
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
