/**
 * Copyright (c) 2015 Joey Hewitt
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.scintill.rilextender;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import com.SecUpwN.AIMSICD.utils.CMDProcessor;
import com.SecUpwN.AIMSICD.utils.CommandResult;

import net.scintill.simio.telephony.uicc.IccIoResult;
import net.scintill.simio.telephony.uicc.IccUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Objects;

public class RilExtenderClient {

    private static final String TAG = "RilExtenderClient";

    private Context mContext;
    private int mInjectedPid;

    public RilExtenderClient(Context context) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
            throw new UnsupportedOperationException("RilExtender probably won't work with Lollipop"+
                    " (more accurately, the ART runtime)");
        }

        mInjectedPid = 0;
        mContext = context;
    }

    public void iccIOForApp(int command, int fileid, String path, int p1, int p2, int p3, String data, String pin2, String aid, Message result) {
        iccIOForApp(command, fileid, path, p1, p2, p3, data, pin2, aid, result, 0);
    }

    private void iccIOForApp(final int command, final int fileid, final String path, final int p1,
        final int p2, final int p3, final String data, final String pin2, final String aid,
        final Message result, final int retries) {

        Log.d(TAG, "iccIOForApp out "+command+" "+fileid+" "+path+" "+p1+" "+p2+" "+p3+" "+data+" "+pin2+" "+aid);

        Intent intent = makeIntent("iccio");
        intent.putExtra("command", command);
        intent.putExtra("fileID", fileid);
        intent.putExtra("path", path);
        intent.putExtra("p1", p1);
        intent.putExtra("p2", p2);
        intent.putExtra("p3", p3);
        intent.putExtra("data", data);
        intent.putExtra("pin2", pin2);
        intent.putExtra("aid", aid);

        sendIntent(intent, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "onReceive "+intent);
                try {
                    if (preprocResponse(getResultCode(), getResultExtras(false), retries)) {
                        byte[] payloadBytes = getResultExtras(false).getByteArray("return");
                        IccIoResult iccIoResult;
                        int sw1 = payloadBytes[0] & 0xff;
                        int sw2 = payloadBytes[1] & 0xff;
                        payloadBytes = Arrays.copyOfRange(payloadBytes, 2, payloadBytes.length);

                        Log.d(TAG, "iccIO result=" + sw1 + " " + sw2 + " " + IccUtils.bytesToHexString(payloadBytes));

                        iccIoResult = new IccIoResult(sw1, sw2, payloadBytes);

                        AsyncResult.forMessage(result, iccIoResult, null);
                        result.sendToTarget();
                    } else {
                        iccIOForApp(command, fileid, path, p1, p2, p3, data, pin2, aid, result, retries + 1);
                    }
                } catch (Throwable t) {
                    AsyncResult.forMessage(result, null, t);
                    result.sendToTarget();
                }
            }
        });
    }

    public Bundle pingSync() throws RemoteException {
        final Intent intent = makeIntent("ping");
        final Message msg = Message.obtain();
        msg.obj = null;

        HandlerThread handlerThread = new HandlerThread("pingSyncTmpThread.RilExtenderClient");
        handlerThread.start();
        final Handler handler = new Handler(handlerThread.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                sendIntent(intent, new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        Log.d(TAG, "pingSync onReceive");
                        try {
                            if (preprocResponse(getResultCode(), getResultExtras(false), 0)) {
                                // this is on the main thread, the outer method is not
                                synchronized (msg) {
                                    msg.obj = getResultExtras(false).clone();
                                    msg.notifyAll();
                                }
                            } else {
                                throw new RemoteException("wasn't initialized");
                            }
                        } catch (RemoteException e) {
                            synchronized (msg) {
                                msg.obj = e;
                                msg.notifyAll();
                            }
                        }
                    }
                }, handler);
            }
        });

        synchronized (msg) {
            try {
                if (msg.obj == null) {
                    msg.wait();
                }
            } catch (InterruptedException e) {
                return null;
            }
        }

        handlerThread.quit();

        if (msg.obj instanceof RemoteException) {
            throw (RemoteException) msg.obj;
        }

        Log.d(TAG, "pingSync exit");
        Bundle bundle = (Bundle)msg.obj;
        msg.recycle();
        return bundle;
    }

    private void installServiceAndWait() {
        boolean shouldInject = false;
        String libSuffix = "/librilinject.so";
        final String libraryDir = mContext.getApplicationInfo().nativeLibraryDir;
        final String libraryPath = libraryDir + libSuffix;

        // We'll remember if we've recently injected it, but maybe the app has restarted since
        // then, so check the actual running process.
        Pair<Integer, Integer> phonePidUid = getPhonePidUid();
        if (phonePidUid == null) {
            throw new RuntimeException("unable to locate phone process");
        }
        final int phonePid = phonePidUid.first;
        int phoneUid = phonePidUid.second;

        if (mInjectedPid == 0 || phonePid != mInjectedPid) {
            try {
                shouldInject = !checkIfLibraryAlreadyLoaded(phonePid, libSuffix);
            } catch (IOException e) {
                Log.e(TAG, "Error trying to determine if library is loaded. Not injecting.", e);
            }
        }

        if (shouldInject) {
            prepareDexFile(phoneUid);

            Log.d(TAG, "Installing RilExtender service");

            // XXX race conditions on loading it.. I guess dlopen()'s idempotent behavior makes it not too bad
            final Message signal = Message.obtain();
            signal.obj = null;
            HandlerThread handlerThread = new HandlerThread("installServiceAndWait.RilExtenderClient");
            handlerThread.start();
            final Handler handler = new Handler(handlerThread.getLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    CommandResult result = CMDProcessor.runSuCommand("logwrapper " + libraryDir + "/lib__hijack.bin__.so -d -p " + phonePid +
                            " -l " + libraryPath);

                    if (!result.success()) {
                        throw new RuntimeException("unable to inject phone process: " + result);
                    }

                    mInjectedPid = phonePid;

                    mContext.registerReceiver(new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            synchronized (signal) {
                                signal.obj = new Boolean(true);
                                signal.notifyAll();
                            }
                        }
                    }, new IntentFilter("net.scintill.rilextender.I_AM_RILEXTENDER"), null, handler);
                }
            });

            synchronized (signal) {
                if (signal.obj == null) {
                    try {
                        signal.wait(5000);
                    } catch (InterruptedException e) {
                        // do nothing
                    }
                }
            }

            handlerThread.quit();

            if (signal.obj == null) {
                signal.recycle();
                throw new RuntimeException("did not hear from new RilExtender service");
            }
            signal.recycle();
        } else {
            Log.e(TAG, "Library was already injected.");
        }
    }

    private boolean checkIfLibraryAlreadyLoaded(int phonePid, String libSuffix) throws IOException {
        BufferedReader in = null;
        boolean sawStack = false, sawLib = false;
        try {
            String filePath = "/proc/"+phonePid+"/maps";
            CommandResult result = CMDProcessor.runSuCommand("cat "+filePath);
            if (!result.success()) {
                throw new IOException("error reading "+filePath);
            }

            in = new BufferedReader(new StringReader(result.getStdout()));
            String line;

            while ((line = in.readLine()) != null) {
                // sanity-check that we are reading correctly
                if (line.endsWith("[stack]")) {
                    sawStack = true;
                    if (sawLib) break;
                } else if (line.contains(libSuffix)) {
                    // this match is looser than I'd like, but it's to handle "lib.so (deleted)"
                    sawLib = true;
                    if (sawStack) break;
                }
            }
        } finally {
            if (in != null) in.close();
        }

        if (!sawStack) {
            throw new IOException("did not find stack; is the file being read wrong?");
        }

        return sawLib;
    }

    private void prepareDexFile(int phoneUid) {
        File rilExtenderDexCacheDir = mContext.getDir("rilextender-cache", Context.MODE_PRIVATE);
        File rilExtenderDex = new File(mContext.getDir("rilextender", Context.MODE_PRIVATE), "rilextender.dex");

        if (rilExtenderDex.getAbsolutePath().equals("/data/data/net.scintill.simfilereader/app_rilextender/rilextender.dex") == false) {
            throw new RuntimeException("The dex wasn't placed where the hardcoded NDK injector expects it! Path was "+rilExtenderDex.getAbsolutePath());
            // We could probably have the NDK injector check several paths if this is a problem.
        }
        if (rilExtenderDexCacheDir.getAbsolutePath().equals("/data/data/net.scintill.simfilereader/app_rilextender-cache") == false) {
            throw new RuntimeException("The dex cache wasn't placed where the hardcoded NDK injector expects it! Path was "+rilExtenderDexCacheDir.getAbsolutePath());
        }

        // TODO cache this?
        try {
            // Extract dex file from assets.
            // Thanks to https://github.com/creativepsyco/secondary-dex-gradle/blob/method2/app/src/main/java/com/github/creativepsyco/secondarydex/plugin/SecondaryDex.java
            // for the general idea.
            InputStream in = new BufferedInputStream(mContext.getAssets().open("rilextender.dex"));
            OutputStream out = new BufferedOutputStream(new FileOutputStream(rilExtenderDex));

            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.close(); // closes target too
            in.close(); // closes source too
        } catch (IOException e) {
            throw new RuntimeException("I/O error while extracting dex", e);
            // if the file is missing, the builder can try re-building the APK and run again
        }

        Log.d(TAG, rilExtenderDex.getName()+" extracted to "+rilExtenderDex.getAbsolutePath());

        // Make sure readable to the phone process.
        if (!rilExtenderDex.setReadable(true, false)) {
            throw new RuntimeException("chmod on dex failed");
        }

        // Can't be world-writable for security, and has to be writable by the phone
        // process (even if I dexopt it from here...)
        // If I put the dexopt'd file beside the .dex, with name .odex, it works; but
        // gives some warnings in the log, and is not really worth the trouble.
        // I also don't really like doing this every time, but I think the permissions could
        // change (for example, backup/restore or something), and I can't see a convenient way
        // to check the owner, so it's easiest to just set it every time.
        CMDProcessor.runSuCommand("chown "+phoneUid+":"+phoneUid+" /data/data/net.scintill.simfilereader/app_rilextender-cache");
    }

    protected Pair<Integer, Integer> getPhonePidUid() {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.processName.equalsIgnoreCase("com.android.phone")) {
                return new Pair<>(info.pid, info.uid);
            }
        }

        return null;
    }

    protected Intent makeIntent(String actionSuffix) {
        Intent intent = new Intent("net.scintill.rilextender."+actionSuffix);
        // https://developer.android.com/reference/android/content/Context.html#registerReceiver(android.content.BroadcastReceiver, android.content.IntentFilter)
        // "As of ICE_CREAM_SANDWICH, receivers registered with this method will correctly respect the setPackage(String) specified for an Intent being broadcast. Prior to that, it would be ignored and delivered to all matching registered receivers. Be careful if using this for security."
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            intent.setPackage("com.android.phone");
        } else {
            throw new RuntimeException("messages can't be securely passed on this version of Android");
            // though, it is not as critical going this way, as it is that other apps can't spy on SIM data
        }
        return intent;
    }

    protected void sendIntent(Intent intent, BroadcastReceiver receiver) {
        mContext.sendOrderedBroadcast(intent, null, receiver, null, 0, null, null);
    }

    protected void sendIntent(Intent intent, BroadcastReceiver receiver, Handler handler) {
        mContext.sendOrderedBroadcast(intent, null, receiver, handler, 0, null, null);
    }

    protected boolean preprocResponse(int resultCode, Bundle extras, int retries) throws RemoteException {
        if (resultCode == 0) {
            // 0 means the service did not receive the broadcast
            if (retries < 1) {
                installServiceAndWait();
            } else {
                throw new RemoteException("service not responding");
            }
            return false;
        } else if (resultCode == -1) {
            // service sent an exception
            throw new RemoteException(extras.getString("exception"));
        } else {
            // service succeeded
            return true;
        }
    }

}
