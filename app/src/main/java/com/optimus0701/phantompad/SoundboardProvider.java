package com.optimus0701.phantompad;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.FileNotFoundException;
import com.optimus0701.phantompad.log.Logger;

public class SoundboardProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) return null;

        String callingApp = getCallingPackage();
        Logger.d("SoundboardProvider: Request from package -> " + callingApp);

        SharedPreferences prefs = context.getSharedPreferences("phantom_mic_module", Context.MODE_PRIVATE);
        
        String targetUriStr = uri.getQueryParameter("target");
        
        if (targetUriStr == null) {
            if (callingApp != null) {
                targetUriStr = prefs.getString("app_" + callingApp, null);
            }
            if (targetUriStr == null) {
                targetUriStr = prefs.getString("global_audio", null);
            }
        }

        if (targetUriStr == null) {
            Logger.d("SoundboardProvider: No audio mapped for " + callingApp);
            throw new FileNotFoundException("No audio assigned for " + callingApp);
        }

        Uri targetUri = Uri.parse(targetUriStr);
        Logger.d("SoundboardProvider: Serving " + targetUriStr);
        
        try {
            return context.getContentResolver().openFileDescriptor(targetUri, "r");
        } catch (Exception e) {
            Logger.d("SoundboardProvider: Error opening target Uri " + e.getMessage());
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "audio/*";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        String callingApp = null;
        if (values != null && values.containsKey("package")) {
            callingApp = values.getAsString("package");
        }
        if (callingApp == null) {
            callingApp = getCallingPackage();
        }
        
        if (callingApp != null) {
            Context context = getContext();
            if (context != null) {
                SharedPreferences prefs = context.getSharedPreferences("phantom_mic_module", Context.MODE_PRIVATE);
                prefs.edit().putBoolean("hooked_" + callingApp, true).apply();
                Logger.d("SoundboardProvider: Registered hooked app -> " + callingApp);
            }
        }
        return uri;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
