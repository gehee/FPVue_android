package com.geehe.fpvue;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.geehe.fpvue.databinding.ActivityVideoBinding;
import com.geehe.fpvue.osd.OSDElement;
import com.geehe.fpvue.osd.OSDManager;
import com.geehe.mavlink.MavlinkData;
import com.geehe.mavlink.MavlinkNative;
import com.geehe.mavlink.MavlinkUpdate;
import com.geehe.videonative.DecodingInfo;
import com.geehe.videonative.IVideoParamsChanged;
import com.geehe.videonative.VideoPlayer;
import com.geehe.wfbngrtl8812.WfbNGStats;
import com.geehe.wfbngrtl8812.WfbNGStatsChanged;
import com.geehe.wfbngrtl8812.WfbNgLink;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.PercentFormatter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import me.saket.cascade.CascadePopupMenuCheckable;

// Most basic implementation of an activity that uses VideoNative to stream a video
// Into an Android Surface View
public class VideoActivity extends AppCompatActivity implements IVideoParamsChanged, WfbNGStatsChanged, MavlinkUpdate, SettingsChanged {
    private static final int PICK_DOCUMENT_REQUEST_CODE = 1;
    private static final int REQUEST_WRITE_PERMISSION = 2;

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

    private ParcelFileDescriptor dvrFd = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVideoBinding.inflate(getLayoutInflater());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Init wfb ng.
        setDefaultGsKey();
        copyGSKey();
        wfbLink = new WfbNgLink(VideoActivity.this);
        wfbLink.SetWfbNGStatsChanged(VideoActivity.this);
        usbManager = new UsbManager(this, binding, wfbLink);
        usbManager.initWifiAdapters();

        // Setup video players
        setContentView(binding.getRoot());
        videoPlayerH264 = new VideoPlayer(this);
        videoPlayerH264.setIVideoParamsChanged(this);
        binding.svH264.getHolder().addCallback(videoPlayerH264.configure1());

        videoPlayerH265 = new VideoPlayer(this);
        videoPlayerH265.setIVideoParamsChanged(this);
        binding.svH265.getHolder().addCallback(videoPlayerH265.configure1());

        osdManager = new OSDManager(this, binding);
        osdManager.setUp();

        PieChart chart = binding.pcLinkStat;
        chart.getLegend().setEnabled(false);
        chart.getDescription().setEnabled(false);
        chart.setDrawHoleEnabled(true);
        chart.setHoleColor(Color.WHITE);
        chart.setTransparentCircleColor(Color.WHITE);
        chart.setTransparentCircleAlpha(110);
        chart.setHoleRadius(58f);
        chart.setTransparentCircleRadius(61f);
        chart.setHighlightPerTapEnabled(false);
        chart.setRotationEnabled(false);
        chart.setClickable(false);
        chart.setTouchEnabled(false);
        PieData noData = new PieData(new PieDataSet(new ArrayList<>(), ""));
        chart.setData(noData);

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
            String codecPref = getCodec(this);
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
            String lockLabel = osdManager.isOSDLocked() ? "Unlock OSD" : "Lock OSD";
            MenuItem lock = osd.add(lockLabel);
            lock.setOnMenuItemClickListener(item -> {
                osdManager.lockOSD(!osdManager.isOSDLocked());
                return true;
            });
            for (OSDElement element: osdManager.listOSDItems) {
                MenuItem itm = osd.add(element.name);
                itm.setCheckable(true);
                itm.setChecked(osdManager.isElementEnabled(element));
                itm.setOnMenuItemClickListener(item -> {
                    item.setChecked(!item.isChecked());
                    osdManager.onOSDItemCheckChanged(element, item.isChecked());
                    return true;
                });
            }

            // WFB
            SubMenu wfb = popup.getMenu().addSubMenu("WFB-NG");
            MenuItem keyBtn = wfb.add("gs.key");
            keyBtn.setOnMenuItemClickListener(item -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, PICK_DOCUMENT_REQUEST_CODE);
                return true;
            });

            // recording
            MenuItem dvrBtn = popup.getMenu().add(dvrFd == null ? "Start recording" : "Stop recording");
            dvrBtn.setOnMenuItemClickListener(item -> {
                if (dvrFd == null) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                        // Permission is not granted
                        ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                REQUEST_WRITE_PERMISSION);
                        return true;
                    }
                    Uri dvrUri = openDvrFile();
                    if (dvrUri != null) {
                        startDvr(dvrUri);
                    }
                } else {
                    stopDvr();
                }
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

    private Uri openDvrFile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore to create the file in the Movies directory
            ContentValues values = new ContentValues();

            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
            // Format the current date and time
            String formattedNow = now.format(formatter);

            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "fpvue_dvr_"+formattedNow);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);
            return getContentResolver().insert(MediaStore.Video.Media.getContentUri("external"), values);
        } else {
            // For Android 9 and below, use traditional file path
            File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            if (!moviesDir.exists() && !moviesDir.mkdirs()) {
                Toast.makeText(this, "Failed to create directory", Toast.LENGTH_SHORT).show();
                return null;
            }
            //File newFile = new File(moviesDir, "fpvue_dvr.mp4");
            // TODO return file descriptor
        }
        return null;
    }

    private void startDvr(Uri dvrUri) {
        if (dvrFd != null) {
           stopDvr();
        }
        try {
            dvrFd = getContentResolver().openFileDescriptor(dvrUri, "rw");
            currentPlayer().startDvr(dvrFd.getFd());
        } catch (IOException e) {
            Log.e(TAG, "Failed to open dvr file ", e);
            dvrFd = null;
        }
    }

    private void stopDvr() {
        if (dvrFd == null) {
            return;
        }
        currentPlayer().stopDvr();
        dvrFd = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_DOCUMENT_REQUEST_CODE && resultCode == RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                Log.d(TAG, "Selected file " + uri);
                try {
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    setGsKey(inputStream);
                    copyGSKey();
                    wfbLink.refreshKey();
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to import gs.key from " + uri);
                }
            }
        }
    }

    public void setDefaultGsKey() {
        if (getGsKey().length > 0 ){
            Log.d(TAG,  "gs.key already saved in preferences.");
            return;
        }
        try {
            Log.d(TAG,  "Importing default gs.key...");
            InputStream inputStream =  getAssets().open("gs.key");;
            setGsKey(inputStream);
            inputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to import default gs.key");
        }
    }

    public byte[] getGsKey() {
        String pref = getSharedPreferences("general", Context.MODE_PRIVATE).getString("gs.key", "");
        return Base64.decode(pref, Base64.DEFAULT);
    }

    public void setGsKey(InputStream inputStream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        SharedPreferences prefs = this.getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("gs.key", Base64.encodeToString(result.toByteArray(), Base64.DEFAULT));
        editor.apply();
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
        super.onPause();
    }

    @Override
    protected void onStop() {
        MavlinkNative.nativeStop(this);
        unregisterReceivers();
        usbManager.stopAdapters();
        stopVideoPlayer();
        super.onStop();
    }

    @Override
    protected void onResume() {
        // On resume can be called when a device is attached, make sure wfb is not running already.
        if (!wfbLink.isRunning()){
            registerReceivers();
            startVideoPlayer();
            usbManager.startAdapters(getChannel(this));
            osdManager.restoreOSDConfig();
        }
        super.onResume();
    }

    protected VideoPlayer currentPlayer() {
        return getCodec(this).equals("h265") ? videoPlayerH265 : videoPlayerH264;
    }

    public synchronized void startVideoPlayer() {
        String codec = getCodec(this);
        if (codec.equals("h265")) {
            videoPlayerH264.stop();
            videoPlayerH265.start(codec);
            binding.svH264.setVisibility(View.INVISIBLE);
            binding.svH265.setVisibility(View.VISIBLE);
        } else {
            videoPlayerH265.stop();
            videoPlayerH264.start(codec);
            binding.svH265.setVisibility(View.INVISIBLE);
            binding.svH264.setVisibility(View.VISIBLE);
        }
        activeCodec=codec;
    }

    public synchronized void stopVideoPlayer() {
        videoPlayerH264.stop();
        videoPlayerH265.stop();
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
        stopVideoPlayer();
        startVideoPlayer();
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
                    binding.tvMessage.setText("");
                    if (data.count_p_dec_err > 0) {
                        binding.tvLinkStatus.setText("Waiting for session key.");
                    } else {
                        // NOTE: The order of the entries when being added to the entries array determines their position around the center of
                        // the chart.
                        ArrayList<PieEntry> entries = new ArrayList<>();
                        entries.add(new PieEntry((float) data.count_p_dec_ok/data.count_p_all));
                        entries.add(new PieEntry((float) data.count_p_fec_recovered/data.count_p_all));
                        entries.add(new PieEntry((float) data.count_p_lost/data.count_p_all));
                        PieDataSet dataSet = new PieDataSet(entries, "Link Status");
                        dataSet.setDrawIcons(false);
                        dataSet.setDrawValues(false);
                        ArrayList<Integer> colors = new ArrayList<>();
                        colors.add(getColor(R.color.colorGreen));
                        colors.add(getColor(R.color.colorYellow));
                        colors.add(getColor(R.color.colorRed));
                        dataSet.setColors(colors);
                        PieData pieData = new PieData(dataSet);
                        pieData.setValueFormatter(new PercentFormatter());
                        pieData.setValueTextSize(11f);
                        pieData.setValueTextColor(Color.WHITE);

                        binding.pcLinkStat.setData(pieData);
                        binding.pcLinkStat.setCenterText(""+data.count_p_fec_recovered);
                        binding.pcLinkStat.invalidate();

                        int color = getColor(R.color.colorGreenBg);
                        if ((float)data.count_p_fec_recovered/data.count_p_all>0.2) {
                            color = getColor(R.color.colorYellowBg);
                        }
                        if (data.count_p_lost>0) {
                            color = getColor(R.color.colorRedBg);
                        }
                        binding.imgLinkStatus.setImageTintList(ColorStateList.valueOf(color));
                        binding.tvLinkStatus.setText(String.format("O%sD%sR%sL%s",
                                paddedDigits(data.count_p_outgoing, 6),
                                paddedDigits(data.count_p_dec_ok, 6),
                                paddedDigits(data.count_p_fec_recovered, 6),
                                paddedDigits(data.count_p_lost, 6)));
                    }
                } else {
                    binding.tvLinkStatus.setText("No wfb-ng data.");
                }
            }
        });
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

    static String paddedDigits(int val, int len) {
        StringBuilder sb = new StringBuilder(String.format("%d", val));
        while (sb.length() < len) {
            sb.append('\t');
        }
        return sb.toString();
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b: bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                // Append a leading zero for single digit hex values
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private void copyGSKey() {
        File file = new File(getApplicationContext().getFilesDir(), "gs.key");
        OutputStream out = null;
        try {
            byte[] keyBytes = getGsKey();
            Log.d(TAG, "Using gs.key:"+bytesToHex(keyBytes)+"; Copying to" + file.getAbsolutePath());
            out = new FileOutputStream(file);
            out.write(keyBytes, 0, keyBytes.length);
        } catch(IOException e) {
            Log.e("tag", "Failed to copy asset", e);
        }
        finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // NOOP
                }
            }
        }
    }
}
