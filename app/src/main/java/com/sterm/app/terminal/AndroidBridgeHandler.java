package com.sterm.app.terminal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.sterm.app.TermuxActivity;
import com.sterm.app.TermuxService;
import com.sterm.shared.logger.Logger;
import com.sterm.shared.termux.TermuxConstants;
import com.sterm.terminal.TerminalOutput;

import java.util.Locale;

/**
 * Handler for STermux OSC 9002 Native Android Bridge commands.
 * Provides direct, zero-IPC access to Android features from the terminal.
 */
public final class AndroidBridgeHandler {

    private static final String LOG_TAG = "AndroidBridgeHandler";

    private static TextToSpeech sTts = null;

    private AndroidBridgeHandler() {}

    public static void handleCommand(@NonNull Context context, @NonNull TerminalOutput session, String textParameter) {
        if (textParameter == null || textParameter.trim().isEmpty() || "HELP".equalsIgnoreCase(textParameter.trim())) {
            session.write("\033]9002;HELP;TOAST,SNACKBAR,VIBRATE,KEYBOARD,OPEN,TORCH,CLIP,BATTERY,VOLUME,TTS,WAKELOCK\007");
            return;
        }

        int semi = textParameter.indexOf(';');
        String subcmd = (semi < 0 ? textParameter : textParameter.substring(0, semi)).trim().toUpperCase(Locale.ENGLISH);
        String rest = (semi < 0 ? "" : textParameter.substring(semi + 1)).trim();

        try {
            switch (subcmd) {
                case "SNACKBAR":
                    handleSnackbar(context, rest);
                    break;
                case "TOAST":
                    handleToast(context, rest);
                    break;
                case "VIBRATE":
                    handleVibrate(context, rest);
                    break;
                case "KEYBOARD":
                    handleKeyboard(context, rest);
                    break;
                case "OPEN":
                    handleOpen(context, rest);
                    break;
                case "TORCH":
                    handleTorch(context, rest);
                    break;
                case "CLIP":
                    handleClipboard(context, session, rest);
                    break;
                case "BATTERY":
                    handleBattery(context, session, rest);
                    break;
                case "VOLUME":
                    handleVolume(context, session, rest);
                    break;
                case "WAKELOCK":
                    handleWakeLock(context, session, rest);
                    break;
                case "TTS":
                    handleTts(context, rest);
                    break;
                default:
                    Logger.logWarn(LOG_TAG, "Unknown OSC 9002 subcmd: " + subcmd);
                    break;
            }
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Error executing OSC 9002 command [" + subcmd + "]: " + t.getMessage());
        }
    }

    private static void handleToast(Context context, String text) {
        if (text.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Toast error: " + t.getMessage());
            }
        });
    }

    // Protocol: SNACKBAR;<message>[;<action>[;<duration_ms>]]
    // If Activity not available, falls back to Toast.
    private static void handleSnackbar(Context context, String args) {
        if (args.isEmpty()) return;

        // Parse: message;action;duration
        String[] parts = args.split(";", 3);
        String message  = parts[0].trim();
        String action   = parts.length > 1 ? parts[1].trim() : "";
        int duration    = com.google.android.material.snackbar.Snackbar.LENGTH_SHORT;
        if (parts.length > 2) {
            try {
                duration = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException ignored) {}
        }

        if (message.isEmpty()) return;

        final String finalMessage = message;
        final String finalAction  = action;
        final int    finalDuration = duration;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                android.view.View rootView = null;
                if (context instanceof TermuxActivity) {
                    rootView = ((TermuxActivity) context).findViewById(android.R.id.content);
                }
                if (rootView == null) {
                    // Fallback to Toast if no Activity view available
                    Toast.makeText(context.getApplicationContext(), finalMessage, Toast.LENGTH_SHORT).show();
                    return;
                }
                com.google.android.material.snackbar.Snackbar snackbar =
                    com.google.android.material.snackbar.Snackbar.make(rootView, finalMessage, finalDuration);
                if (!finalAction.isEmpty()) {
                    snackbar.setAction(finalAction, v -> {});
                }
                snackbar.show();
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Snackbar error: " + t.getMessage());
            }
        });
    }

    private static void handleVibrate(Context context, String arg) {
        long ms = 100;
        if (!arg.isEmpty()) {
            try {
                ms = Math.max(1, Math.min(10000, Long.parseLong(arg)));
            } catch (NumberFormatException ignored) {}
        }

        final long duration = ms;
        try {
            Vibrator vibrator = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vibrator = vm.getDefaultVibrator();
            }
            if (vibrator == null) {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(duration);
                }
            }
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Vibrate error: " + t.getMessage());
        }
    }

    private static void handleKeyboard(Context context, String arg) {
        final boolean show = "1".equals(arg) || "SHOW".equalsIgnoreCase(arg);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (context instanceof TermuxActivity) {
                    TermuxActivity act = (TermuxActivity) context;
                    InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        if (show) {
                            act.getTerminalView().requestFocus();
                            imm.showSoftInput(act.getTerminalView(), InputMethodManager.SHOW_IMPLICIT);
                        } else {
                            imm.hideSoftInputFromWindow(act.getTerminalView().getWindowToken(), 0);
                        }
                    }
                }
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Keyboard error: " + t.getMessage());
            }
        });
    }

    private static void handleOpen(Context context, String url) {
        if (url.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Uri uri = Uri.parse(url);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Open error: " + t.getMessage());
            }
        });
    }

    private static void handleTorch(Context context, String arg) {
        boolean enable = "1".equals(arg) || "ON".equalsIgnoreCase(arg);
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) {
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                    Boolean flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    if (Boolean.TRUE.equals(flashAvailable) &&
                            (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                        cm.setTorchMode(id, enable);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Torch error: " + t.getMessage());
        }
    }

    private static void handleClipboard(Context context, TerminalOutput session, String rest) {
        int semi = rest.indexOf(';');
        String sub = (semi < 0 ? rest : rest.substring(0, semi)).trim().toUpperCase(Locale.ENGLISH);
        String text = (semi < 0 ? "" : rest.substring(semi + 1));

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cb = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb == null) return;

                if ("SET".equals(sub)) {
                    cb.setPrimaryClip(ClipData.newPlainText("STermux", text));
                } else if ("GET".equals(sub) || rest.isEmpty()) {
                    ClipData clip = cb.getPrimaryClip();
                    String clipText = (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null)
                            ? clip.getItemAt(0).getText().toString() : "";
                    session.write(String.format(Locale.ENGLISH, "\033]9002;CLIP;GET;%s\007", clipText));
                }
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Clipboard error: " + t.getMessage());
            }
        });
    }

    private static void handleBattery(Context context, TerminalOutput session, String sub) {
        Intent intent = null;
        try {
            intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable ignored) {}

        int level = -1;
        int scale = 100;
        int status = BatteryManager.BATTERY_STATUS_UNKNOWN;
        int plugged = 0;
        float temp = 0.0f;
        int voltage = 0;

        if (intent != null) {
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f;
            voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        } else {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        }

        int percent = (scale > 0 && level >= 0) ? (level * 100) / scale : level;

        String statusStr;
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: statusStr = "CHARGING"; break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING: statusStr = "DISCHARGING"; break;
            case BatteryManager.BATTERY_STATUS_FULL: statusStr = "FULL"; break;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: statusStr = "NOT_CHARGING"; break;
            default: statusStr = "UNKNOWN"; break;
        }

        String pluggedStr;
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC: pluggedStr = "AC"; break;
            case BatteryManager.BATTERY_PLUGGED_USB: pluggedStr = "USB"; break;
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: pluggedStr = "WIRELESS"; break;
            default: pluggedStr = "NONE"; break;
        }

        String req = sub.toUpperCase(Locale.ENGLISH).trim();
        switch (req) {
            case "LEVEL":
            case "PERCENT":
                session.write(String.format(Locale.ENGLISH, "\033]9002;BATTERY;LEVEL;%d\007", percent));
                break;
            case "STATE":
                session.write(String.format(Locale.ENGLISH, "\033]9002;BATTERY;STATE;%s\007", statusStr));
                break;
            case "TEMP":
                session.write(String.format(Locale.ENGLISH, "\033]9002;BATTERY;TEMP;%.1f\007", temp));
                break;
            case "STAT":
            default:
                // Full status: \e]9002;BATTERY;STAT;<level>;<status>;<plugged>;<temp_C>;<voltage_mV>\a
                session.write(String.format(Locale.ENGLISH,
                    "\033]9002;BATTERY;STAT;%d;%s;%s;%.1f;%d\007",
                    percent, statusStr, pluggedStr, temp, voltage));
                break;
        }
    }

    private static void handleVolume(Context context, TerminalOutput session, String rest) {
        int semi = rest.indexOf(';');
        String sub = (semi < 0 ? rest : rest.substring(0, semi)).trim().toUpperCase(Locale.ENGLISH);
        String arg = (semi < 0 ? "" : rest.substring(semi + 1)).trim();

        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);

            if ("SET".equals(sub)) {
                int percent = Math.max(0, Math.min(100, Integer.parseInt(arg)));
                int target = (percent * max) / 100;
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            } else {
                // GET
                int percent = max > 0 ? (cur * 100) / max : 0;
                session.write(String.format(Locale.ENGLISH, "\033]9002;VOLUME;GET;%d\007", percent));
            }
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Volume error: " + t.getMessage());
        }
    }

    private static void handleWakeLock(Context context, TerminalOutput session, String arg) {
        boolean enable = "1".equals(arg) || "ON".equalsIgnoreCase(arg);
        try {
            TermuxService service = null;
            if (context instanceof TermuxActivity) {
                service = ((TermuxActivity) context).getTermuxService();
            } else if (context instanceof TermuxService) {
                service = (TermuxService) context;
            }
            if (service == null) {
                Logger.logError(LOG_TAG, "WakeLock: TermuxService not available");
                return;
            }
            if (enable) {
                service.actionAcquireWakeLock();
            } else {
                service.actionReleaseWakeLock(true);
            }
            final TermuxService finalService = service;
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "WakeLock " + (finalService.isWakeLockHeld() ? "ON" : "OFF"), Toast.LENGTH_SHORT).show()
            );
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "WakeLock error: " + t.getMessage());
        }
    }

    private static void handleTts(Context context, String text) {
        if (text.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (sTts == null) {
                    sTts = new TextToSpeech(context.getApplicationContext(), status -> {
                        if (status == TextToSpeech.SUCCESS) {
                            try {
                                sTts.setLanguage(Locale.getDefault());
                                sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sterm_tts");
                            } catch (Throwable ignored) {}
                        }
                    });
                } else {
                    sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sterm_tts");
                }
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "TTS error: " + t.getMessage());
            }
        });
    }
}
