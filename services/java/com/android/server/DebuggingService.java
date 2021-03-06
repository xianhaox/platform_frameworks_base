/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IDebuggingManager;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;
import android.util.Base64;
import com.android.server.FgThread;

import java.lang.Thread;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.util.Arrays;

public class DebuggingService extends IDebuggingManager.Stub implements Runnable {
    private static final String TAG = "DebuggingService";
    private static final boolean DEBUG = false;

    private final String ADBD_SOCKET = "adbd";
    private final String ADB_DIRECTORY = "misc/adb";
    private final String ADB_KEYS_FILE = "adb_keys";
    private final int BUFFER_SIZE = 4096;

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final Handler mHandler;
    private Thread mThread;
    private boolean mAdbEnabled = false;
    private String mFingerprints;
    private LocalSocket mSocket = null;
    private OutputStream mOutputStream = null;

    private class AdbSettingsObserver extends ContentObserver {
        public AdbSettingsObserver() {
            super(null);
        }
        @Override
        public void onChange(boolean selfChange) {
            boolean enable = (Settings.Global.getInt(mContentResolver,
                    Settings.Global.ADB_ENABLED, 0) > 0);
            setAdbEnabled(enable);
        }
    }

    private final BroadcastReceiver mBootCompletedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean adbEnabled = (Settings.Global.getInt(mContentResolver,
                    Settings.Global.ADB_ENABLED, 0) > 0);
            setAdbEnabled(adbEnabled);
        }
    };

    public DebuggingService(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();

        // register observer to listen for settings changes
        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                false, new AdbSettingsObserver());
        // register intent filter for boot complete to start the service
        mContext.registerReceiver(
                mBootCompletedReceiver, new IntentFilter(Intent.ACTION_BOOT_COMPLETED));

        mHandler = new DebuggingHandler(FgThread.get().getLooper());
    }

    private void listenToSocket() throws IOException {
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            LocalSocketAddress address = new LocalSocketAddress(ADBD_SOCKET,
                                         LocalSocketAddress.Namespace.RESERVED);
            InputStream inputStream = null;

            mSocket = new LocalSocket();
            mSocket.connect(address);

            mOutputStream = mSocket.getOutputStream();
            inputStream = mSocket.getInputStream();

            while (true) {
                int count = inputStream.read(buffer);
                if (count < 0) {
                    Slog.e(TAG, "got " + count + " reading");
                    break;
                }

                if (buffer[0] == 'P' && buffer[1] == 'K') {
                    String key = new String(Arrays.copyOfRange(buffer, 2, count));
                    Slog.d(TAG, "Received public key: " + key);
                    Message msg = mHandler.obtainMessage(DebuggingHandler.MESSAGE_ADB_CONFIRM);
                    msg.obj = key;
                    mHandler.sendMessage(msg);
                }
                else {
                    Slog.e(TAG, "Wrong message: " + (new String(Arrays.copyOfRange(buffer, 0, 2))));
                    break;
                }
            }
        } catch (IOException ex) {
            Slog.e(TAG, "Communication error: ", ex);
            throw ex;
        } finally {
            closeSocket();
        }
    }

    @Override
    public void run() {
        while (mAdbEnabled) {
            try {
                listenToSocket();
            } catch (Exception e) {
                /* Don't loop too fast if adbd dies, before init restarts it */
                SystemClock.sleep(1000);
            }
        }
    }

    private void closeSocket() {
        if (mOutputStream != null) {
            try {
                mOutputStream.close();
            } catch (IOException e) {
                Slog.e(TAG, "Failed closing output stream: " + e);
            }
        }

        if (mSocket != null) {
            try {
                mSocket.getInputStream().close();
                mSocket.close();
            } catch (IOException ex) {
                Slog.e(TAG, "Failed closing socket: " + ex);
            }
        }
    }

    private void sendResponse(String msg) {
        if (mOutputStream != null) {
            try {
                mOutputStream.write(msg.getBytes());
            }
            catch (IOException ex) {
                Slog.e(TAG, "Failed to write response:", ex);
            }
        }
    }

    class DebuggingHandler extends Handler {
        private static final int MESSAGE_ADB_ENABLED = 1;
        private static final int MESSAGE_ADB_DISABLED = 2;
        private static final int MESSAGE_ADB_ALLOW = 3;
        private static final int MESSAGE_ADB_DENY = 4;
        private static final int MESSAGE_ADB_CONFIRM = 5;
        private static final int MESSAGE_ADB_CLEAR = 6;

        public DebuggingHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_ADB_ENABLED:
                    if (mAdbEnabled)
                        break;

                    mAdbEnabled = true;
                    mThread = new Thread(DebuggingService.this, TAG);
                    mThread.start();

                    break;

                case MESSAGE_ADB_DISABLED:
                    if (!mAdbEnabled)
                        break;

                    mAdbEnabled = false;
                    closeSocket();

                    try {
                        mThread.join();
                    } catch (Exception ex) {
                    }

                    mThread = null;
                    mOutputStream = null;
                    mSocket = null;

                    break;

                case MESSAGE_ADB_ALLOW: {
                    String key = (String)msg.obj;
                    String fingerprints = getFingerprints(key);

                    if (!fingerprints.equals(mFingerprints)) {
                        Slog.e(TAG, "Fingerprints do not match. Got "
                                + fingerprints + ", expected " + mFingerprints);
                        break;
                    }

                    if (msg.arg1 == 1) {
                        writeKey(key);
                    }

                    sendResponse("OK");
                    break;
                }

                case MESSAGE_ADB_DENY:
                    sendResponse("NO");
                    break;

                case MESSAGE_ADB_CONFIRM: {
                    String key = (String)msg.obj;
                    String fingerprints = getFingerprints(key);
                    if ("".equals(fingerprints)) {
                        sendResponse("NO");
                        break;
                    }
                    mFingerprints = fingerprints;
                    startConfirmation(key, mFingerprints);
                    break;
                }

                case MESSAGE_ADB_CLEAR:
                    deleteKeyFile();
                    break;
            }
        }
    }

    private String getFingerprints(String key) {
        String hex = "0123456789ABCDEF";
        StringBuilder sb = new StringBuilder();
        MessageDigest digester;

        if (key == null) {
            return "";
        }

        try {
            digester = MessageDigest.getInstance("MD5");
        } catch (Exception ex) {
            Slog.e(TAG, "Error getting digester", ex);
            return "";
        }

        byte[] base64_data = key.split("\\s+")[0].getBytes();
        byte[] digest;
        try {
            digest = digester.digest(Base64.decode(base64_data, Base64.DEFAULT));
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "error doing base64 decoding", e);
            return "";
        }
        for (int i = 0; i < digest.length; i++) {
            sb.append(hex.charAt((digest[i] >> 4) & 0xf));
            sb.append(hex.charAt(digest[i] & 0xf));
            if (i < digest.length - 1)
                sb.append(":");
        }
        return sb.toString();
    }

    private void startConfirmation(String key, String fingerprints) {
        String nameString = Resources.getSystem().getString(
                com.android.internal.R.string.config_customAdbPublicKeyConfirmationComponent);
        ComponentName componentName = ComponentName.unflattenFromString(nameString);
        if (startConfirmationActivity(componentName, key, fingerprints)
                || startConfirmationService(componentName, key, fingerprints)) {
            return;
        }
        Slog.e(TAG, "unable to start customAdbPublicKeyConfirmationComponent "
                + nameString + " as an Activity or a Service");
    }

    /**
     * @returns true if the componentName led to an Activity that was started.
     */
    private boolean startConfirmationActivity(ComponentName componentName, String key,
            String fingerprints) {
        PackageManager packageManager = mContext.getPackageManager();
        Intent intent = createConfirmationIntent(componentName, key, fingerprints);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            try {
                mContext.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException e) {
                Slog.e(TAG, "unable to start adb whitelist activity: " + componentName, e);
            }
        }
        return false;
    }

    /**
     * @returns true if the componentName led to a Service that was started.
     */
    private boolean startConfirmationService(ComponentName componentName, String key,
            String fingerprints) {
        Intent intent = createConfirmationIntent(componentName, key, fingerprints);
        try {
            if (mContext.startService(intent) != null) {
                return true;
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "unable to start adb whitelist service: " + componentName, e);
        }
        return false;
    }

    private Intent createConfirmationIntent(ComponentName componentName, String key,
            String fingerprints) {
        Intent intent = new Intent();
        intent.setClassName(componentName.getPackageName(), componentName.getClassName());
        intent.putExtra("key", key);
        intent.putExtra("fingerprints", fingerprints);
        return intent;
    }

    private File getUserKeyFile() {
        File dataDir = Environment.getDataDirectory();
        File adbDir = new File(dataDir, ADB_DIRECTORY);

        if (!adbDir.exists()) {
            Slog.e(TAG, "ADB data directory does not exist");
            return null;
        }

        return new File(adbDir, ADB_KEYS_FILE);
    }

    private void writeKey(String key) {
        try {
            File keyFile = getUserKeyFile();

            if (keyFile == null) {
                return;
            }

            if (!keyFile.exists()) {
                keyFile.createNewFile();
                FileUtils.setPermissions(keyFile.toString(),
                    FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                    FileUtils.S_IRGRP, -1, -1);
            }

            FileOutputStream fo = new FileOutputStream(keyFile, true);
            fo.write(key.getBytes());
            fo.write('\n');
            fo.close();
        }
        catch (IOException ex) {
            Slog.e(TAG, "Error writing key:" + ex);
        }
    }

    private void deleteKeyFile() {
        File keyFile = getUserKeyFile();
        if (keyFile != null) {
            keyFile.delete();
        }
    }

    public void setAdbEnabled(boolean enabled) {
        mHandler.sendEmptyMessage(enabled ? DebuggingHandler.MESSAGE_ADB_ENABLED
                                          : DebuggingHandler.MESSAGE_ADB_DISABLED);
    }

    public void allowDebugging(boolean alwaysAllow, String publicKey) {
        Message msg = mHandler.obtainMessage(DebuggingHandler.MESSAGE_ADB_ALLOW);
        msg.arg1 = alwaysAllow ? 1 : 0;
        msg.obj = publicKey;
        mHandler.sendMessage(msg);
    }

    public void denyDebugging() {
        mHandler.sendEmptyMessage(DebuggingHandler.MESSAGE_ADB_DENY);
    }

    public void clearDebuggingKeys() {
        mHandler.sendEmptyMessage(DebuggingHandler.MESSAGE_ADB_CLEAR);
    }
}
