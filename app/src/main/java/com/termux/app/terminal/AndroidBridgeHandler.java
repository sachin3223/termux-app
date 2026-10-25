package com.termux.app.terminal;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalOutput;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Handler for Termux OSC 9002 Native Android Bridge commands.
 * Provides direct, zero-IPC access to Android features from the terminal.
 */
public final class AndroidBridgeHandler {

    private static final String LOG_TAG = "AndroidBridgeHandler";

    private static TextToSpeech sTts = null;
    private static CancellationSignal sAuthCancellationSignal = null;

    private AndroidBridgeHandler() {}

    public static void handleCommand(@NonNull Context context, @NonNull TerminalOutput session, String textParameter) {
        if (textParameter == null || textParameter.trim().isEmpty() || "HELP".equalsIgnoreCase(textParameter.trim())) {
            session.write("\033]9002;HELP;TOAST,SNACKBAR,VIBRATE,KEYBOARD,OPEN,TORCH,CLIP,BATTERY,VOLUME,TTS,WAKELOCK,AUTH,INSTALL\007");
            return;
        }

        int semi = textParameter.indexOf(';');
        String subcmd = (semi < 0 ? textParameter : textParameter.substring(0, semi)).trim().toUpperCase(Locale.ENGLISH);
        String rest = (semi < 0 ? "" : textParameter.substring(semi + 1)).trim();

        try {
            switch (subcmd) {
                case "INSTALL":
                    handleInstall(context, session, rest);
                    break;
                case "AUTH":
                    handleAuth(context, session, rest);
                    break;
                case "SNACKBAR":
                    handleSnackbar(context, session, rest);
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
    private static void handleSnackbar(Context context, TerminalOutput session, String args) {
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
                // Track if action button was pressed
                final boolean[] actionPressed = {false};
                if (!finalAction.isEmpty()) {
                    snackbar.setAction(finalAction, v -> actionPressed[0] = true);
                }
                // Respond to terminal when done (script can read result)
                snackbar.addCallback(new com.google.android.material.snackbar.Snackbar.Callback() {
                    @Override
                    public void onDismissed(com.google.android.material.snackbar.Snackbar sb, int event) {
                        String result = actionPressed[0] ? "ACTION" : "DISMISS";
                        session.write("\033]9002;SNACKBAR;" + result + "\007");
                    }
                });
                // Push Snackbar above keyboard if open
                android.view.View snackbarView = snackbar.getView();
                androidx.core.view.WindowInsetsCompat insets =
                    androidx.core.view.ViewCompat.getRootWindowInsets(rootView);
                if (insets != null) {
                    int imeHeight = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
                    android.view.ViewGroup.MarginLayoutParams params =
                        (android.view.ViewGroup.MarginLayoutParams) snackbarView.getLayoutParams();
                    params.bottomMargin = imeHeight;
                    snackbarView.setLayoutParams(params);
                }
                // Swipe left/right to dismiss (like Chrome)
                android.view.GestureDetector gd = new android.view.GestureDetector(context,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                               float vX, float vY) {
                            if (Math.abs(vX) > Math.abs(vY) * 1.5f) {
                                snackbar.dismiss();
                                return true;
                            }
                            return false;
                        }
                    });
                snackbarView.setOnTouchListener((v, event) -> gd.onTouchEvent(event));
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
                    cb.setPrimaryClip(ClipData.newPlainText("Termux", text));
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
                                sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "termux_tts");
                            } catch (Throwable ignored) {}
                        }
                    });
                } else {
                    sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "termux_tts");
                }
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "TTS error: " + t.getMessage());
            }
        });
    }

    // Protocol: AUTH[;<title>[;<subtitle>]]
    // Responds: \033]9002;AUTH;SUCCESS\007
    //           \033]9002;AUTH;CANCELLED\007
    //           \033]9002;AUTH;FAILED;<reason>\007
    private static void handleAuth(Context context, TerminalOutput session, String args) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                TermuxActivity activity = null;
                if (context instanceof TermuxActivity) {
                    activity = (TermuxActivity) context;
                } else if (context instanceof TermuxService) {
                    activity = ((TermuxService) context).getActivity();
                }

                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    session.write("\033]9002;AUTH;FAILED;Activity not in foreground\007");
                    return;
                }

                KeyguardManager km = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
                if (km == null || !km.isDeviceSecure()) {
                    session.write("\033]9002;AUTH;FAILED;Device lock screen is not set\007");
                    return;
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    session.write("\033]9002;AUTH;FAILED;Biometrics require Android 9+\007");
                    return;
                }

                String title = "Authenticate";
                String subtitle = "Confirm your identity to proceed";
                if (!args.isEmpty()) {
                    String[] parts = args.split(";", 2);
                    if (!parts[0].trim().isEmpty()) {
                        title = parts[0].trim();
                    }
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                        subtitle = parts[1].trim();
                    }
                }

                if (sAuthCancellationSignal != null) {
                    try {
                        sAuthCancellationSignal.cancel();
                    } catch (Throwable ignored) {}
                    sAuthCancellationSignal = null;
                }

                sAuthCancellationSignal = new CancellationSignal();
                final CancellationSignal currentSignal = sAuthCancellationSignal;

                BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setDescription(subtitle);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    AuthHelperApi30.setAuthenticators(builder);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setDeviceCredentialAllowed(true);
                } else {
                    builder.setNegativeButton("Cancel", activity.getMainExecutor(), (dialog, which) -> {
                        session.write("\033]9002;AUTH;CANCELLED\007");
                    });
                }

                BiometricPrompt prompt = builder.build();
                prompt.authenticate(currentSignal, activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        sAuthCancellationSignal = null;
                        session.write("\033]9002;AUTH;SUCCESS\007");
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        sAuthCancellationSignal = null;
                        if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                            errorCode == 13 /* BIOMETRIC_ERROR_NEGATIVE_BUTTON */) {
                            session.write("\033]9002;AUTH;CANCELLED\007");
                        } else {
                            String msg = (errString != null) ? errString.toString() : ("Error " + errorCode);
                            session.write("\033]9002;AUTH;FAILED;" + msg + "\007");
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                    }
                });

            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Auth error: " + t.getMessage());
                session.write("\033]9002;AUTH;FAILED;" + t.getMessage() + "\007");
            }
        });
    }

    private static class AuthHelperApi30 {
        static void setAuthenticators(BiometricPrompt.Builder builder) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            );
        }
    }

    // Protocol: INSTALL;<apk_path>
    // Responds: \033]9002;INSTALL;SUCCESS\007
    //           \033]9002;INSTALL;PERMISSION_REQUIRED;<msg>\007
    //           \033]9002;INSTALL;FAILED;<reason>\007
    private static void handleInstall(Context context, TerminalOutput session, String path) {
        if (path == null || path.trim().isEmpty()) {
            session.write("\033]9002;INSTALL;FAILED;No APK path provided\007");
            return;
        }

        String apkPath = path.trim();
        if (apkPath.startsWith("~/")) {
            apkPath = TermuxConstants.TERMUX_HOME_DIR_PATH + apkPath.substring(1);
        } else if (!apkPath.startsWith("/")) {
            apkPath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/" + apkPath;
        }

        File file = new File(apkPath);
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            session.write("\033]9002;INSTALL;FAILED;File not found or unreadable: " + apkPath + "\007");
            return;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!context.getPackageManager().canRequestPackageInstalls()) {
                        Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        permIntent.setData(Uri.parse("package:" + context.getPackageName()));
                        permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(permIntent);
                        session.write("\033]9002;INSTALL;PERMISSION_REQUIRED;Please grant Install Unknown Apps permission\007");
                        return;
                    }
                }

                // If file is in internal storage, cache to external cache directory so SELinux doesn't block PackageInstaller
                File apkToInstall = file;
                if (!file.getAbsolutePath().startsWith("/sdcard/") && !file.getAbsolutePath().startsWith("/storage/")) {
                    File extCache = context.getExternalCacheDir();
                    if (extCache != null) {
                        File cachedApk = new File(extCache, "install_temp.apk");
                        try (java.io.InputStream in = new java.io.FileInputStream(file);
                             java.io.OutputStream out = new java.io.FileOutputStream(cachedApk)) {
                            byte[] buf = new byte[65536];
                            int len;
                            while ((len = in.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                            apkToInstall = cachedApk;
                        } catch (Throwable t) {
                            Logger.logWarn(LOG_TAG, "Failed to cache APK to external cache: " + t.getMessage());
                        }
                    }
                }

                Uri contentUri = UriUtils.getContentUri(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, apkToInstall.getAbsolutePath());
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

                // Directly target genuine system Package Installer (Manufacturer/OEM first, Google as last resort)
                PackageManager pm = context.getPackageManager();
                List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
                String targetPkg = null;
                String fallbackPkg = null;
                String manufacturer = Build.MANUFACTURER.toLowerCase(Locale.ENGLISH);

                for (ResolveInfo ri : resolveInfos) {
                    if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                        String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ENGLISH);
                        if (pkg.contains("termux") || pkg.contains("termux") || pkg.contains("editor")) {
                            continue;
                        }
                        // 1. TOP PRIORITY: Manufacturer/Brand specific installer (vivo, bbk, samsung, miui, oppo, etc.)
                        if (pkg.contains(manufacturer) || pkg.contains("vivo") || pkg.contains("bbk") ||
                            pkg.contains("samsung") || pkg.contains("miui") || pkg.contains("coloros")) {
                            targetPkg = ri.activityInfo.packageName;
                            break;
                        }
                        // 2. AOSP generic package installer
                        if (pkg.equals("com.android.packageinstaller")) {
                            fallbackPkg = ri.activityInfo.packageName;
                        }
                        // 3. Other OEM installer
                        else if (fallbackPkg == null && pkg.contains("packageinstaller") && !pkg.contains("google")) {
                            fallbackPkg = ri.activityInfo.packageName;
                        }
                        // 4. Last resort: Google package installer
                        else if (fallbackPkg == null && pkg.contains("packageinstaller")) {
                            fallbackPkg = ri.activityInfo.packageName;
                        }
                    }
                }
                if (targetPkg == null) {
                    targetPkg = fallbackPkg;
                }
                if (targetPkg != null) {
                    intent.setPackage(targetPkg);
                }

                context.startActivity(intent);
                session.write("\033]9002;INSTALL;SUCCESS\007");
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Install error: " + t.getMessage());
                session.write("\033]9002;INSTALL;FAILED;" + t.getMessage() + "\007");
            }
        });
    }
}
