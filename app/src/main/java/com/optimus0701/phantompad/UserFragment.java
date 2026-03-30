package com.optimus0701.phantompad;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class UserFragment extends Fragment {

    private SharedPreferences mPrefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_user, container, false);
        
        if (getContext() != null) {
            mPrefs = getContext().getSharedPreferences("phantom_mic_module", Context.MODE_PRIVATE);
            
            Button btnSwitchLanguage = view.findViewById(R.id.btn_switch_language);
            btnSwitchLanguage.setOnClickListener(v -> {
                String currentLang = mPrefs.getString("app_language", "");
                String newLang = currentLang.equals("vi") ? "en" : "vi";
                mPrefs.edit().putString("app_language", newLang).apply();
                
                // Restart Activity to apply language changes
                if (getActivity() != null) {
                    Intent intent = getActivity().getIntent();
                    getActivity().finish();
                    startActivity(intent);
                }
            });
        }
        
        return view;
    }
}
