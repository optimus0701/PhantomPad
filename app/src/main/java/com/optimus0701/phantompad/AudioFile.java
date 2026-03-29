package com.optimus0701.phantompad;

import android.net.Uri;

public class AudioFile {
    public String name;
    public Uri uri;

    public AudioFile(String name, Uri uri) {
        this.name = name;
        this.uri = uri;
    }
}
