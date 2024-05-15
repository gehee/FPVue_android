package com.geehe.fpvue.osd;
import android.content.Context;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.geehe.fpvue.R;
import java.util.List;

public class ControlOSDItemAdapter extends ArrayAdapter<String> {
    private SparseBooleanArray itemCheckedStates;
    private OnOSDItemCheckChangeListener onItemCheckChangeListener;
    private static final String TAG = "com.geehe.fpvue";

    public interface OnOSDItemCheckChangeListener {
        void onOSDItemCheckChanged(int position, boolean isChecked);
    }
    public ControlOSDItemAdapter(Context context, List<String> items) {
        super(context, 0, items);
        itemCheckedStates = new SparseBooleanArray(items.size());
    }

    public void setItemCheckChangeListener(OnOSDItemCheckChangeListener listener) {
        this.onItemCheckChangeListener = listener;
    }

    @NonNull
    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_with_checkbox, parent, false);
        }

        CheckBox checkBox = convertView.findViewById(R.id.checkBox);
        TextView textView = convertView.findViewById(R.id.tvItem);

        String item = getItem(position);
        Log.d("TAG", "getView: " + String.valueOf(item));
        textView.setText(item);

        checkBox.setOnCheckedChangeListener(null); // Clear listener
        checkBox.setChecked(itemCheckedStates.get(position, false)); // Set the current state

        checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                itemCheckedStates.put(position, isChecked);
                // Notify the listener that the checkbox state has changed
                if (onItemCheckChangeListener != null) {
                    onItemCheckChangeListener.onOSDItemCheckChanged(position, isChecked);
                }
            }
        });

        return convertView;
    }

    public SparseBooleanArray getCheckedItemPositions() {
        return itemCheckedStates;
    }

    public void setCheckedItemPositions(SparseBooleanArray checkedItemPositions) {
        this.itemCheckedStates = checkedItemPositions;
        notifyDataSetChanged();
    }
}
