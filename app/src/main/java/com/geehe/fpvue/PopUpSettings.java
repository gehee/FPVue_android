package com.geehe.fpvue;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.TextView;


public class PopUpSettings implements AdapterView.OnItemSelectedListener {
    private final SharedPreferences preferences;
    private final LayoutInflater inflater;
    private final View parent;
    private Spinner spChannel;
    private Spinner spCodec;
    private SettingsChanged changeCallback;

    public PopUpSettings(final View view, final SharedPreferences prefs, SettingsChanged changeCb) {
        preferences = prefs;
        inflater = (LayoutInflater) view.getContext().getSystemService(view.getContext().LAYOUT_INFLATER_SERVICE);
        parent = view;
        changeCallback = changeCb;
    }

    public void showPopupWindow() {
        View popupView = inflater.inflate(R.layout.settings, null);

        int width = 800;
        int height = LinearLayout.LayoutParams.WRAP_CONTENT;
        final PopupWindow popupWindow = new PopupWindow(popupView, width, height, true /*focusable*/);
        popupWindow.showAtLocation(parent, Gravity.CENTER, 0, 0);

        spChannel = (Spinner)popupView.findViewById(R.id.spChannel);
        spCodec = (Spinner)popupView.findViewById(R.id.spCodec);
        spChannel.setOnItemSelectedListener(this);
        spCodec.setOnItemSelectedListener(this);

        String codec = preferences.getString("codec", "h265");
        ArrayAdapter<String> codecAdapter = (ArrayAdapter<String>)spCodec.getAdapter();
        int codecPos = codecAdapter.getPosition(codec);
        if( codecPos >= 0) {
            spCodec.setSelection(codecPos);
            Log.d("Settings", "Restored preference codec " + codec);
        }
        int wifiChannel = preferences.getInt("wifi-channel", 11);
        ArrayAdapter<String> channelAdapter = (ArrayAdapter<String>)spChannel.getAdapter();
        int chnPos = channelAdapter.getPosition(wifiChannel+"");
        if( chnPos >= 0) {
            spChannel.setSelection(chnPos);
            Log.d("Settings", "Restored preference channel " + wifiChannel);
        }

        //Handler for clicking on the inactive zone of the window
        popupView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                //Close the window when clicked
                popupWindow.dismiss();
                return true;
            }
        });
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        SharedPreferences.Editor editor = preferences.edit();
        if (parent == spChannel) {
            String value = (String) parent.getItemAtPosition(position);
            int channel = Integer.parseInt(value);
            editor.putInt("wifi-channel",  channel);
            editor.apply();
            Log.d("Settings", "Saved preference channel " + value);
            changeCallback.onChannelSettingChanged(channel);
        } else if (parent == spCodec) {
            String value = (String) parent.getItemAtPosition(position);
            editor.putString("codec",  value);
            editor.apply();
            Log.d("Settings", "Saved preference codec " + value);
            changeCallback.onCodecSettingChanged(value);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }
}