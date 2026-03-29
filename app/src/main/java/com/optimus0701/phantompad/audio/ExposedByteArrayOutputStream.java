package com.optimus0701.phantompad.audio;

import java.io.ByteArrayOutputStream;

public class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
    public byte[] getBuffer() {
        return buf;
    }
}
