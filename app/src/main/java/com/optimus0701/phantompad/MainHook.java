package com.optimus0701.phantompad;

import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import com.optimus0701.phantompad.log.Logger;

public class MainHook implements IXposedHookLoadPackage {
    private PhantomManager phantomManager = null;

    private boolean needHook = true;

    private String packageName;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (needHook) {
            needHook = false;

            packageName = lpparam.packageName;
            System.loadLibrary("xposedlab");

            Logger.d("Beginning hook");
            doHook(lpparam);
            Logger.d("Successful hook");
        }
    }

    private void doHook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.media.MediaRecorder", lpparam.classLoader, "start" , new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Logger.d("MediaRecorder start");
            }
        });

        XposedHelpers.findAndHookConstructor("android.media.AudioRecord", lpparam.classLoader,
            int.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int audioSource = (int) param.args[0];
                // VOICE_COMMUNICATION (7) applies heavy noise suppression that destroys music.
                // Change it to VOICE_RECOGNITION (6) or MIC (1) for raw, unprocessed audio.
                if (audioSource == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    param.args[0] = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION;
                    Logger.d("AudioRecord Constructor: Forced AudioSource from VOICE_COMMUNICATION to VOICE_RECOGNITION to bypass hardware filtering.");
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.media.AudioRecord", lpparam.classLoader, "startRecording", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                android.media.AudioRecord record = (android.media.AudioRecord) param.thisObject;
                int sampleRate = record.getSampleRate();
                int channelCount = record.getChannelCount();
                int audioFormat = record.getAudioFormat();
                int session = record.getAudioSessionId();
                int source = record.getAudioSource();

                Logger.d("AudioRecord startRecording -> SampleRate: " + sampleRate + 
                        "Hz, Channels: " + channelCount + 
                        ", Format: " + audioFormat + 
                        ", Source: " + source +
                        ", Session: " + session);

                // Force VOICE_COMMUNICATION -> VOICE_RECOGNITION via reflection
                // This is the ONLY reliable way on Android 13 where native set_hook fails
                if (source == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    try {
                        java.lang.reflect.Field sourceField = android.media.AudioRecord.class.getDeclaredField("mRecordSource");
                        sourceField.setAccessible(true);
                        sourceField.setInt(record, android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION);
                        Logger.d("AudioRecord: Forced mRecordSource VOICE_COMMUNICATION(7) -> VOICE_RECOGNITION(6) via reflection");
                    } catch (NoSuchFieldException e) {
                        Logger.d("AudioRecord: mRecordSource field not found, trying alternative...");
                    } catch (Exception e) {
                        Logger.d("AudioRecord: Failed to set mRecordSource: " + e.getMessage());
                    }
                }

                // Disable privacy-sensitive mode (Android 11+)
                // This prevents Discord from getting enhanced audio processing
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try {
                        java.lang.reflect.Method m = android.media.AudioRecord.class.getMethod("setPrivacySensitive", boolean.class);
                        m.invoke(record, false);
                        Logger.d("AudioRecord: setPrivacySensitive(false)");
                    } catch (Exception e) {}
                }

                // Let PhantomManager know about the exact format requested
                if (phantomManager != null) {
                    try {
                        phantomManager.updateAudioFormat(sampleRate, record.getChannelConfiguration(), audioFormat);
                    } catch (Exception e) {}
                }
            }
        });

        // Bypass WebRTC Noise Suppression and Echo Cancellation which destroys injected music
        XC_MethodHook disableAudioEffectHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.getResult() != null) {
                    android.media.audiofx.AudioEffect effect = (android.media.audiofx.AudioEffect) param.getResult();
                    // Force the effect to be disabled so it doesn't filter our music
                    try {
                        effect.setEnabled(false);
                        Logger.d("Disabled AudioEffect: " + effect.getDescriptor().name);
                    } catch (Exception e) {}
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod("android.media.audiofx.NoiseSuppressor", lpparam.classLoader, "create", int.class, disableAudioEffectHook);
            XposedHelpers.findAndHookMethod("android.media.audiofx.AcousticEchoCanceler", lpparam.classLoader, "create", int.class, disableAudioEffectHook);
            XposedHelpers.findAndHookMethod("android.media.audiofx.AutomaticGainControl", lpparam.classLoader, "create", int.class, disableAudioEffectHook);
        } catch (Throwable t) {
            Logger.d("Failed to hook AudioEffects: " + t.getMessage());
        }

        // ── Discord/WebRTC-specific hooks ──────────────────────────────────
        // Discord creates AudioRecord via AudioRecord.Builder (not the 5-arg constructor).
        // Without hooking the Builder, VOICE_COMMUNICATION cannot be overridden.

        // 1) Hook AudioRecord.Builder.setAudioSource to force VOICE_RECOGNITION
        try {
            XposedHelpers.findAndHookMethod("android.media.AudioRecord$Builder", lpparam.classLoader,
                "setAudioSource", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int source = (int) param.args[0];
                        if (source == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                            param.args[0] = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION;
                            Logger.d("AudioRecord.Builder: Forced AudioSource VOICE_COMMUNICATION -> VOICE_RECOGNITION");
                        }
                    }
                });
        } catch (Throwable t) {
            Logger.d("Failed to hook AudioRecord.Builder.setAudioSource: " + t.getMessage());
        }

        // 2) Hook AudioRecord.Builder.build() to disable privacy-sensitive flag
        //    and capture the created AudioRecord's actual format
        try {
            XposedHelpers.findAndHookMethod("android.media.AudioRecord$Builder", lpparam.classLoader,
                "build", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // On Android 11+ (API 30), disable privacy-sensitive mode.
                        // When enabled, Discord gets exclusive audio processing that filters music.
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            try {
                                XposedHelpers.callMethod(param.thisObject, "setPrivacySensitive", false);
                                Logger.d("AudioRecord.Builder: Forced setPrivacySensitive(false)");
                            } catch (Throwable ignored) {}
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.getResult() != null) {
                            android.media.AudioRecord record = (android.media.AudioRecord) param.getResult();
                            Logger.d("AudioRecord.Builder.build() -> SampleRate: " + record.getSampleRate() +
                                    "Hz, Channels: " + record.getChannelCount() +
                                    ", Format: " + record.getAudioFormat() +
                                    ", Source: " + record.getAudioSource());
                            if (phantomManager != null) {
                                try {
                                    phantomManager.updateAudioFormat(
                                        record.getSampleRate(),
                                        record.getChannelConfiguration(),
                                        record.getAudioFormat());
                                } catch (Exception e) {}
                            }
                        }
                    }
                });
        } catch (Throwable t) {
            Logger.d("Failed to hook AudioRecord.Builder.build: " + t.getMessage());
        }

        // 3) Broad AudioEffect disable: catch ANY audio effect being enabled
        //    (covers WebRTC using effects beyond NS/AEC/AGC)
        try {
            XposedHelpers.findAndHookMethod("android.media.audiofx.AudioEffect", lpparam.classLoader,
                "setEnabled", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        boolean enabling = (boolean) param.args[0];
                        if (enabling) {
                            param.args[0] = false;
                            android.media.audiofx.AudioEffect effect = (android.media.audiofx.AudioEffect) param.thisObject;
                            try {
                                Logger.d("Blocked AudioEffect.setEnabled(true) for: " + effect.getDescriptor().name);
                            } catch (Exception ignored) {}
                        }
                    }
                });
        } catch (Throwable t) {
            Logger.d("Failed to hook AudioEffect.setEnabled: " + t.getMessage());
        }

        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application application = (Application) param.args[0];
                if (phantomManager == null) {
                    initPhantomManager(application.getApplicationContext());
                }
            }
        });
    }

    private void initPhantomManager(Context context) {
        phantomManager = new PhantomManager(context, isNativeHook());
        try {
            android.net.Uri uri = android.net.Uri.parse("content://com.optimus0701.phantompad.provider/register");
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("package", packageName);
            context.getContentResolver().insert(uri, values);
            Logger.d("MainHook: Registered package with module");
        } catch (Exception e) {
            Logger.d("MainHook: Failed to register hook scope: " + e.getMessage());
        }

        try {
            android.content.IntentFilter filter = new android.content.IntentFilter("com.optimus0701.phantompad.ACTION_CONTROL");
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, android.content.Intent intent) {
                    String cmd = intent.getStringExtra("cmd");
                    Logger.d("MainHook: Received broadcast cmd=" + cmd);
                    if ("play".equals(cmd)) {
                        boolean mixAudio = intent.getBooleanExtra("mix_audio", false);
                        boolean playLocal = intent.getBooleanExtra("play_local", false);
                        String filePath = intent.getStringExtra("file_path");
                        String uriStr = intent.getStringExtra("uri");
                        phantomManager.setMixAudio(mixAudio);
                        phantomManager.setPlayLocal(playLocal);
                        phantomManager.setPaused(false);
                        Logger.d("MainHook: Loading audio... (mix=" + mixAudio + ", local=" + playLocal + ")");
                        if (filePath != null) {
                            phantomManager.loadFromFile(filePath);
                        } else if (uriStr != null) {
                            phantomManager.load(uriStr);
                        } else {
                            phantomManager.load();
                        }
                    } else if ("pause".equals(cmd)) {
                        Logger.d("MainHook: Pausing audio...");
                        phantomManager.setPaused(true);
                    } else if ("resume".equals(cmd)) {
                        Logger.d("MainHook: Resuming audio...");
                        phantomManager.setPaused(false);
                    } else if ("stop".equals(cmd)) {
                        Logger.d("MainHook: Stopping audio...");
                        phantomManager.setPaused(false);
                        phantomManager.unload();
                    }
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            Logger.d("MainHook: Registered live broadcast receiver");
        } catch (Exception e) {
            Logger.d("MainHook: Failed to register receiver: " + e.getMessage());
        }
    }

    public boolean isNativeHook() {
        return true;
    }
}

