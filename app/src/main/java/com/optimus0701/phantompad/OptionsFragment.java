package com.optimus0701.phantompad;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

public class OptionsFragment extends Fragment {

    private SharedPreferences mPrefs;
    private static final boolean REQUIRE_PREMIUM = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_options, container, false);
        
        if (getContext() != null) {
            mPrefs = getContext().getSharedPreferences("phantom_mic_module", Context.MODE_PRIVATE);
            
            // ── Mix Audio Switch ──
            MaterialSwitch switchMixAudio = view.findViewById(R.id.switch_mix_audio);
            switchMixAudio.setOnCheckedChangeListener(null);
            boolean mixAudio = mPrefs.getBoolean("mix_audio", false);
            switchMixAudio.setChecked(mixAudio);
            switchMixAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked && REQUIRE_PREMIUM && !checkPremium()) {
                    buttonView.setChecked(false);
                    return;
                }
                mPrefs.edit().putBoolean("mix_audio", isChecked).apply();
            });
            
            // ── Play Local Switch ──
            MaterialSwitch switchPlayLocal = view.findViewById(R.id.switch_play_local);
            switchPlayLocal.setOnCheckedChangeListener(null);
            boolean playLocal = mPrefs.getBoolean("play_local", false);
            switchPlayLocal.setChecked(playLocal);
            switchPlayLocal.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked && REQUIRE_PREMIUM && !checkPremium()) {
                    buttonView.setChecked(false);
                    return;
                }
                mPrefs.edit().putBoolean("play_local", isChecked).apply();
            });

            // ── Mic Boost Slider ──
            Slider sliderMicBoost = view.findViewById(R.id.slider_mic_boost);
            TextView tvMicBoostValue = view.findViewById(R.id.tv_mic_boost_value);
            
            float currentBoost = mPrefs.getFloat("mic_boost", 1.0f);
            sliderMicBoost.setValue(currentBoost);
            tvMicBoostValue.setText((int)(currentBoost * 100) + "%");
            
            sliderMicBoost.addOnChangeListener((slider, value, fromUser) -> {
                tvMicBoostValue.setText((int)(value * 100) + "%");
                if (fromUser) {
                    mPrefs.edit().putFloat("mic_boost", value).apply();
                    broadcastCmd("set_mic_boost", intent -> intent.putExtra("boost", value));
                }
            });

            // ── Advanced Audio Settings Dropdown ──
            View btnAdvancedToggle = view.findViewById(R.id.btn_advanced_toggle);
            LinearLayout panelAdvanced = view.findViewById(R.id.panel_advanced);
            ImageView ivArrow = view.findViewById(R.id.iv_advanced_arrow);

            btnAdvancedToggle.setOnClickListener(v -> {
                if (panelAdvanced.getVisibility() == View.GONE) {
                    expandView(panelAdvanced);
                    ivArrow.setRotation(180f);
                } else {
                    collapseView(panelAdvanced);
                    ivArrow.setRotation(0f);
                }
            });

            // ── Boost File Audio (inside dropdown) ──
            MaterialSwitch switchBoostFile = view.findViewById(R.id.switch_boost_file);
            switchBoostFile.setOnCheckedChangeListener(null);
            boolean boostFile = mPrefs.getBoolean("boost_file", false);
            switchBoostFile.setChecked(boostFile);
            switchBoostFile.setOnCheckedChangeListener((buttonView, isChecked) -> {
                mPrefs.edit().putBoolean("boost_file", isChecked).apply();
                broadcastCmd("set_boost_file", intent -> intent.putExtra("enabled", isChecked));
            });

            // ── Noise Gate Threshold Slider ──
            Slider sliderGateThreshold = view.findViewById(R.id.slider_gate_threshold);
            TextView tvGateValue = view.findViewById(R.id.tv_gate_threshold_value);

            float gateThreshold = mPrefs.getFloat("gate_threshold", -40.0f);
            sliderGateThreshold.setValue(gateThreshold);
            tvGateValue.setText((int) gateThreshold + " dB");

            sliderGateThreshold.addOnChangeListener((slider, value, fromUser) -> {
                tvGateValue.setText((int) value + " dB");
                if (fromUser) {
                    mPrefs.edit().putFloat("gate_threshold", value).apply();
                    broadcastCmd("set_gate_threshold", intent -> intent.putExtra("db", value));
                }
            });

            // ── Compressor Threshold Slider ──
            Slider sliderCompThreshold = view.findViewById(R.id.slider_comp_threshold);
            TextView tvCompThresholdValue = view.findViewById(R.id.tv_comp_threshold_value);

            float compThreshold = mPrefs.getFloat("comp_threshold", -20.0f);
            sliderCompThreshold.setValue(compThreshold);
            tvCompThresholdValue.setText((int) compThreshold + " dB");

            sliderCompThreshold.addOnChangeListener((slider, value, fromUser) -> {
                tvCompThresholdValue.setText((int) value + " dB");
                if (fromUser) {
                    mPrefs.edit().putFloat("comp_threshold", value).apply();
                    broadcastCmd("set_comp_threshold", intent -> intent.putExtra("db", value));
                }
            });

            // ── Compressor Ratio Slider ──
            Slider sliderCompRatio = view.findViewById(R.id.slider_comp_ratio);
            TextView tvCompRatioValue = view.findViewById(R.id.tv_comp_ratio_value);

            float compRatio = mPrefs.getFloat("comp_ratio", 4.0f);
            sliderCompRatio.setValue(compRatio);
            tvCompRatioValue.setText(formatRatio(compRatio));

            sliderCompRatio.addOnChangeListener((slider, value, fromUser) -> {
                tvCompRatioValue.setText(formatRatio(value));
                if (fromUser) {
                    mPrefs.edit().putFloat("comp_ratio", value).apply();
                    broadcastCmd("set_comp_ratio", intent -> intent.putExtra("ratio", value));
                }
            });
        }
        
        return view;
    }

    // ── Helpers ──

    private String formatRatio(float ratio) {
        if (ratio == (int) ratio) {
            return (int) ratio + ":1";
        }
        return String.format("%.1f:1", ratio);
    }

    private void broadcastCmd(String cmd, java.util.function.Consumer<android.content.Intent> extras) {
        android.content.Intent intent = new android.content.Intent("com.optimus0701.phantompad.ACTION_CONTROL");
        intent.putExtra("cmd", cmd);
        if (extras != null) extras.accept(intent);
        if (getContext() != null) {
            getContext().sendBroadcast(intent);
        }
    }

    // ── Expand/Collapse Animation ──

    private static void expandView(final View v) {
        v.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        final int targetHeight = v.getMeasuredHeight();

        v.getLayoutParams().height = 0;
        v.setVisibility(View.VISIBLE);

        Animation anim = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                v.getLayoutParams().height = interpolatedTime == 1
                        ? ViewGroup.LayoutParams.WRAP_CONTENT
                        : (int) (targetHeight * interpolatedTime);
                v.setAlpha(interpolatedTime);
                v.requestLayout();
            }

            @Override
            public boolean willChangeBounds() { return true; }
        };
        anim.setDuration(250);
        v.startAnimation(anim);
    }

    private static void collapseView(final View v) {
        final int initialHeight = v.getMeasuredHeight();

        Animation anim = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                if (interpolatedTime == 1) {
                    v.setVisibility(View.GONE);
                } else {
                    v.getLayoutParams().height = initialHeight - (int) (initialHeight * interpolatedTime);
                    v.setAlpha(1f - interpolatedTime);
                    v.requestLayout();
                }
            }

            @Override
            public boolean willChangeBounds() { return true; }
        };
        anim.setDuration(200);
        v.startAnimation(anim);
    }

    // ── Premium Check ──

    private boolean checkPremium() {
        if (mPrefs == null || getContext() == null) return false;
        
        String token = mPrefs.getString("auth_token", null);
        if (token == null || token.isEmpty()) {
            Toast.makeText(getContext(), R.string.user_login_required, Toast.LENGTH_SHORT).show();
            switchToUserTab();
            return false;
        }
        
        boolean isPremium = mPrefs.getBoolean("is_premium", false);
        if (!isPremium) {
            Toast.makeText(getContext(), R.string.user_premium_required, Toast.LENGTH_LONG).show();
            switchToUserTab();
            return false;
        }
        
        return true;
    }

    private void switchToUserTab() {
        if (getActivity() != null) {
            BottomNavigationView nav = getActivity().findViewById(R.id.bottom_navigation);
            if (nav != null) {
                nav.setSelectedItemId(R.id.nav_user);
            }
        }
    }
}
