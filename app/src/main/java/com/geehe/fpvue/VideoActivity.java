package com.geehe.fpvue;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;

import com.geehe.fpvue.databinding.ActivityVideoBinding;
import com.geehe.videonative.AspectFrameLayout;
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
import java.util.Iterator;
import java.util.Objects;

// Most basic implementation of an activity that uses VideoNative to stream a video
// Into an Android Surface View
public class VideoActivity extends AppCompatActivity implements IVideoParamsChanged, AdapterView.OnItemSelectedListener, WfbNGStatsChanged {
    private ActivityVideoBinding binding;
    protected SurfaceView surfaceView;
    private VideoPlayer videoPlayer;
    private TextView textViewStatistics;
    protected DecodingInfo mDecodingInfo;
    int lastVideoW=0,lastVideoH=0;

    static String TAG = "com.geehe.fpvue";

    private static String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    BroadcastReceiver detachReceiver;

    static UsbDevice lastUsbDevice;
    WfbNgLink link;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVideoBinding.inflate(getLayoutInflater());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Setup video player
        setContentView(binding.getRoot());
        surfaceView=binding.svVideo;
        videoPlayer = new VideoPlayer(this);
        videoPlayer.setIVideoParamsChanged(this);
        surfaceView.getHolder().addCallback(videoPlayer.configure1());

        // Build channel selector
        binding.spinner.setOnItemSelectedListener(this);

        copyGSKey();

        detachReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (Objects.equals(intent.getAction(), UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                    Log.d(TAG, "usb detached " + VideoActivity.this);
                    if (link != null) {
                        link.Stop();
                    }
                    binding.tvMessage.setVisibility(View.VISIBLE);
                    binding.tvMessage.setText("Wifi adapter detached.");
                } else if (Objects.equals(intent.getAction(), UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                    Log.d(TAG, "usb attached" + VideoActivity.this);
                    StartWfbNg();
                }
            }
        };

        // Set the intent filter for the broadcast receiver, and register.
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(detachReceiver, filter);
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

    protected void onStop() {
        unregisterReceiver(detachReceiver);
        Log.d(TAG, "onStop");
        if (link != null) {
            link.Stop();
        }
        super.onStop();
    }

    protected void onResume() {
        Log.d(TAG, "onResume");

        SharedPreferences sharedPref =this.getPreferences(Context.MODE_PRIVATE);
        int wifiChannel = sharedPref.getInt("wifi-channel", 11);
        ArrayAdapter<String> channelAdapter = (ArrayAdapter<String>)binding.spinner.getAdapter();
        int pos = channelAdapter.getPosition(wifiChannel+"");
        if( pos > 0) {
            binding.spinner.setSelection(pos);
            Log.d(TAG, "Restored preference channel " + wifiChannel);
        }
        if (lastUsbDevice == null) {
            Log.d(TAG, "lastUsbDevice is null");
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
            for (UsbDevice usbDevice : deviceList.values()) {
                lastUsbDevice = usbDevice;
                Log.d(TAG, "Found adapter" + lastUsbDevice.getDeviceName());
            }
        }


        StartWfbNg();

        super.onResume();
    }

    public void StartWfbNg(){
        SharedPreferences sharedPref =this.getPreferences(Context.MODE_PRIVATE);

        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (!usbManager.hasPermission(lastUsbDevice)) {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText("No permission for USB.");
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(lastUsbDevice, usbPermissionIntent);
            return;
        }

        int wifiChannel = sharedPref.getInt("wifi-channel", 11);
        if (wifiChannel < 0) {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText("No channel selected.");
            return;
        }
        if (lastUsbDevice == null) {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText( "No compatible adapter found.");
            return ;
        }
        binding.tvMessage.setVisibility(View.VISIBLE);
        binding.tvMessage.setText( "Starting wfb-ng on channel " + wifiChannel+ ".");

       try {
           link = new WfbNgLink(VideoActivity.this, usbManager , lastUsbDevice.getDeviceName());
           Log.d(TAG, "wfb-ng link started.");
           link.SetWfbNGStatsChanged(VideoActivity.this);
           Thread thread = new Thread() {
               @Override
               public void run() {
                   link.Run(wifiChannel);
                   Log.d(TAG, "wfb-ng link stopped.");
               }
           };
           thread.start();
       } catch (NullPointerException e) {
           Log.d(TAG, "Adapter unplugged.");
           binding.tvMessage.setText( "Adapter unplugged.");
       }
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
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        ((TextView) parent.getChildAt(0)).setTextColor(Color.WHITE);
        String value = (String) parent.getItemAtPosition(position);
        if (parent == binding.spinner) {
            SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putInt("wifi-channel",  Integer.parseInt(value));
            editor.apply();
        }
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
}