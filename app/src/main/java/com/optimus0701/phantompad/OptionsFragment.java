package com.optimus0701.phantompad;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.materialswitch.MaterialSwitch;

public class OptionsFragment extends Fragment {

    private SharedPreferences mPrefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_options, container, false);
        
        if (getContext() != null) {
            mPrefs = getContext().getSharedPreferences("phantom_mic_module", Context.MODE_PRIVATE);
            
            MaterialSwitch switchMixAudio = view.findViewById(R.id.switch_mix_audio);
            boolean mixAudio = mPrefs.getBoolean("mix_audio", false);
            switchMixAudio.setChecked(mixAudio);
            switchMixAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                mPrefs.edit().putBoolean("mix_audio", isChecked).apply();
                Toast.makeText(getContext(), "Mix Audio: " + (isChecked ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
            });
            
            MaterialSwitch switchPlayLocal = view.findViewById(R.id.switch_play_local);
            boolean playLocal = mPrefs.getBoolean("play_local", false);
            switchPlayLocal.setChecked(playLocal);
            switchPlayLocal.setOnCheckedChangeListener((buttonView, isChecked) -> {
                mPrefs.edit().putBoolean("play_local", isChecked).apply();
                Toast.makeText(getContext(), "Local Playback: " + (isChecked ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
            });
        }
        
        return view;
    }
}
