package com.geehe.fpvue;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.geehe.fpvue.databinding.ActivityVideoBinding;
import com.geehe.mavlink.MavlinkData;
import com.geehe.mavlink.MavlinkNative;
import com.geehe.mavlink.MavlinkUpdate;
import com.geehe.videonative.DecodingInfo;
import com.geehe.videonative.IVideoParamsChanged;
import com.geehe.videonative.VideoPlayer;
import com.geehe.wfbngrtl8812.WfbNGStats;
import com.geehe.wfbngrtl8812.WfbNGStatsChanged;
import com.geehe.wfbngrtl8812.WfbNgLink;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

// Most basic implementation of an activity that uses VideoNative to stream a video
// Into an Android Surface View
public class VideoActivity extends AppCompatActivity implements IVideoParamsChanged, AdapterView.OnItemSelectedListener, WfbNGStatsChanged, MavlinkUpdate {
    private ActivityVideoBinding binding;
    protected SurfaceView surfaceView;
    private TextView textViewStatistics;
    protected DecodingInfo mDecodingInfo;
    int lastVideoW=0,lastVideoH=0;

    private static final String TAG = "com.geehe.fpvue";
    private static final String ACTION_USB_PERMISSION = "com.geehe.fpvue.USB_PERMISSION";

    BroadcastReceiver usbReceiver;
    static UsbDevice lastUsbDevice;
    WfbNgLink wfbLink;
    Thread wfbThread;
    boolean selectionInit = false;
    boolean permissionRefused = false;

    private Timer mavlinkTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        binding = ActivityVideoBinding.inflate(getLayoutInflater());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Setup video player
        setContentView(binding.getRoot());
        surfaceView=binding.svVideo;
        VideoPlayer videoPlayer = new VideoPlayer(this);
        videoPlayer.setIVideoParamsChanged(this);
        surfaceView.getHolder().addCallback(videoPlayer.configure1());

        // Setup mavlink
        MavlinkNative.nativeStart(this);
        mavlinkTimer=new Timer();
        mavlinkTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                MavlinkNative.nativeCallBack(VideoActivity.this);
            }
        },0,1000);

        // Build channel selector
        binding.spinner.setOnItemSelectedListener(this);

        copyGSKey();

        usbReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                synchronized (this) {
                    StopWfbNg();
                    if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                        Log.d(TAG, "usb detached.");
                        binding.tvMessage.setVisibility(View.VISIBLE);
                        binding.tvMessage.setText("Wifi adapter detached.");
                    } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                        Log.d(TAG, "usb attached.");
                    } else if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                        permissionRefused = !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    }
                }
            }
        };
        // Set the intent filter for the broadcast receiver, and register.
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    protected void onStop() {
        MavlinkNative.nativeStop(this);
        unregisterReceiver(usbReceiver);
        Log.d(TAG, "onStop");
        StopWfbNg();
        super.onStop();
    }

    protected void onResume() {
        SharedPreferences sharedPref =this.getPreferences(Context.MODE_PRIVATE);
        int wifiChannel = sharedPref.getInt("wifi-channel", 11);
        ArrayAdapter<String> channelAdapter = (ArrayAdapter<String>)binding.spinner.getAdapter();
        int pos = channelAdapter.getPosition(wifiChannel+"");
        if( pos > 0) {
            binding.spinner.setSelection(pos);
            Log.d(TAG, "Restored preference channel " + wifiChannel);
        }

        Log.d(TAG, "onResume StartWfbNg");
        StartWfbNg();
        super.onResume();
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        ((TextView) parent.getChildAt(0)).setTextColor(Color.WHITE);
        String value = (String) parent.getItemAtPosition(position);
        if (parent == binding.spinner) {
            SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putInt("wifi-channel",  Integer.parseInt(value));
            editor.apply();
            if(selectionInit) {
                StartWfbNg();
            }
            selectionInit=true;
        }
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
        if (lastUsbDevice == null) {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
            for (UsbDevice usbDevice : deviceList.values()) {
                lastUsbDevice = usbDevice;
                Log.d(TAG, "Found adapter" + lastUsbDevice.getDeviceName());
            }
        }
        if (lastUsbDevice == null) {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText( "No compatible wifi adapter found.");
            return ;
        }

        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (!usbManager.hasPermission(lastUsbDevice)) {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText("No permission for wifi adapter.");
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            if (!permissionRefused) {
                usbManager.requestPermission(lastUsbDevice, usbPermissionIntent);
            }
            return;
        }

        binding.tvMessage.setVisibility(View.VISIBLE);
        binding.tvMessage.setText( "Starting wfb-ng on channel " + wifiChannel+ ".");

        try {
            wfbLink = new WfbNgLink(VideoActivity.this, usbManager, lastUsbDevice.getDeviceName());
            Log.d(TAG, "wfb-ng link started.");
            wfbLink.SetWfbNGStatsChanged(VideoActivity.this);
            wfbThread = new Thread() {
                @Override
                public void run() {
                    wfbLink.Run(wifiChannel);
                    Log.d(TAG, "wfb-ng link stopped.");
                }
            };
            wfbThread.start();
        } catch (NullPointerException e) {
            Log.d(TAG, "Adapter unplugged.");
            binding.tvMessage.setText("Adapter unplugged.");
        }
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
        binding.tvMessage.setVisibility(View.INVISIBLE);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (decodingInfo.currentKiloBitsPerSecond > 1000) {
                    binding.tvVideoInfo.setText(String.format("%dx%d   %.0f fps   %.2f MB/s   %.1f ms",lastVideoW, lastVideoH, decodingInfo.currentFPS, decodingInfo.currentKiloBitsPerSecond/1000, decodingInfo.avgTotalDecodingTime_ms));
                } else {
                    binding.tvVideoInfo.setText(String.format("%dx%d   %.0f fps   %.1f KB/s   %.1f ms",lastVideoW, lastVideoH, decodingInfo.currentFPS, decodingInfo.currentKiloBitsPerSecond, decodingInfo.avgTotalDecodingTime_ms));
                }
            }
        });
    }


    @Override
    public void onNothingSelected(AdapterView<?> parent) {}

    private int count_p_dec_err = 0;
    private int count_p_lost = 0;
    private int count_p_fec_recovered = 0;
    private int count_p_outgoing = 0;
    @Override
    public void onWfbNgStatsChanged(WfbNGStats data) {
        binding.tvMessage.setVisibility(View.INVISIBLE);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (data.count_p_all > 0) {
                    int perr = data.count_p_dec_err - count_p_dec_err;
                    if (perr > 0) {
                        binding.tvWFBNGStatus.setText("Waiting for session key.");
                    } else {
                        binding.tvWFBNGStatus.setText(String.format("lost=%d\t\trec=%d\t\tok=%d",
                                data.count_p_lost - count_p_lost,
                                data.count_p_fec_recovered - count_p_fec_recovered,
                                data.count_p_outgoing - count_p_outgoing));
                    }
                } else {
                    binding.tvWFBNGStatus.setText("No wfb-ng data.");
                }
                count_p_dec_err = data.count_p_dec_err;
                count_p_lost = data.count_p_lost;
                count_p_fec_recovered = data.count_p_fec_recovered;
                count_p_outgoing = data.count_p_outgoing;
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

    public String formatDouble(double v, String unit, String prefix) {
        if (v == 0 ) {
            return "";
        }
        return String.format("%s%.2f%s", prefix, v,unit);
    }
    public String formatFloat(float v, String unit, String prefix) {
        if (v == 0 ) {
            return "";
        }
        return String.format("%s%.2f%s", prefix, v, unit);
    }

    @Override
    public void onNewMavlinkData(MavlinkData data) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                binding.tvTelembattery.setText(formatDouble(data.telemetryBattery/1000.0,"V", ""));
                binding.tvTelemCurr.setText(formatFloat(data.telemetryCurrent,"A",""));
                binding.tvTelemCurrCons.setText(formatFloat(data.telemetryCurrentConsumed,"A",""));
                binding.tvTelemAltitude.setText(formatFloat(data.telemetryAltitude,"m","ALT:"));
                binding.tvTelemDistance.setText(formatDouble(data.telemetryDistance,"m","DST:"));
                binding.tvTelemgspeed.setText(formatFloat(data.telemetryGspeed,"m/s","GSPD:"));
                binding.tvTelemvspeed.setText(formatFloat(data.telemetryVspeed,"m/s","VSPD:"));
                binding.tvTelemThrottle.setText(String.format("%.0f", data.telemetryThrottle)+"%  \t");
                binding.tvTelemRoll.setText(formatFloat(data.telemetryRoll,"",""));
                binding.tvTelemPitch.setText(formatDouble(data.telemetryPitch,"",""));
                binding.tvTelemArm.setText(data.telemetryArm > 1000 ? "Armed" : "");
                binding.tvTelemHeading.setText(formatDouble(data.telemetryHdg,"","HDG:"));
                binding.tvTelemLat.setText(formatDouble(data.telemetryLat,"","LAT:"));
                binding.tvTelemLong.setText(formatDouble(data.telemetryLon,"","LON:"));
            }
        });
    }
}