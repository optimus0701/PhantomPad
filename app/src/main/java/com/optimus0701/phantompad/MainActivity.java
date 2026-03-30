package com.optimus0701.phantompad;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    
    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences mPrefs = newBase.getSharedPreferences("phantom_mic_module", Context.MODE_PRIVATE);
        String lang = mPrefs.getString("app_language", "");
        
        if (!lang.isEmpty()) {
            Locale locale = new Locale(lang);
            Locale.setDefault(locale);
            Configuration config = new Configuration(newBase.getResources().getConfiguration());
            config.setLocale(locale);
            newBase = newBase.createConfigurationContext(config);
        }
        super.attachBaseContext(newBase);
    }

    private Fragment homeFragment;
    private Fragment optionsFragment;
    private Fragment userFragment;
    private Fragment activeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivitiesIfAvailable(this.getApplication());
        setContentView(R.layout.activity_main);
        
        homeFragment = new HomeFragment();
        optionsFragment = new OptionsFragment();
        userFragment = new UserFragment();
        activeFragment = homeFragment;

        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, userFragment, "3").hide(userFragment)
                .add(R.id.fragment_container, optionsFragment, "2").hide(optionsFragment)
                .add(R.id.fragment_container, homeFragment, "1")
                .commit();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                selectedFragment = homeFragment;
            } else if (itemId == R.id.nav_options) {
                selectedFragment = optionsFragment;
            } else if (itemId == R.id.nav_user) {
                selectedFragment = userFragment;
            }
            
            if (selectedFragment != null && selectedFragment != activeFragment) {
                getSupportFragmentManager().beginTransaction()
                        .hide(activeFragment)
                        .show(selectedFragment)
                        .commit();
                activeFragment = selectedFragment;
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_home);
        }
    }
}
