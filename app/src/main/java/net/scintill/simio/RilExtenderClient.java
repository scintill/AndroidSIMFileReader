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
package net.scintill.simio;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;

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

        installServiceAndWait();
    }

    public void iccIOForApp(int command, int fileid, String path, int p1, int p2, int p3, String data, String pin2, String aid, final Message
            result) {
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
                // XXX error handling?
                byte[] payloadBytes = getResultExtras(false).getByteArray("return");
                IccIoResult iccIoResult;
                int sw1 = payloadBytes[0] & 0xff;
                int sw2 = payloadBytes[1] & 0xff;
                payloadBytes = Arrays.copyOfRange(payloadBytes, 2, payloadBytes.length);

                Log.d(TAG, "iccIO result=" + sw1 + " " + sw2 + " " + IccUtils.bytesToHexString(payloadBytes));

                iccIoResult = new IccIoResult(sw1, sw2, payloadBytes);

                AsyncResult.forMessage(result, iccIoResult, null);
                result.sendToTarget();
            }
        });
    }

    public Bundle pingSyncNotOnMainThread() {
        Intent intent = makeIntent("ping");
        final Message msg = Message.obtain();
        msg.obj = null;
        Log.d(TAG, "pingSync "+Thread.currentThread().getName());

        sendIntent(intent, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "pingSync onReceive");
                // this should be on the main thread, the outer method is not
                synchronized (msg) {
                    msg.obj = getResultExtras(false).clone();
                    msg.notifyAll();
                }
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
        Log.d(TAG, "pingSync exit");
        Bundle bundle = (Bundle)msg.obj;
        msg.recycle();
        return bundle;
    }

    private void installServiceAndWait() {
        boolean shouldInject = false;
        int phonePid = 0;
        int phoneUid = 0;
        String libraryDir = mContext.getApplicationInfo().nativeLibraryDir;
        String libraryPath = libraryDir + "/librilinject.so";

        // We'll remember if we've recently injected it, but maybe the app has restarted since
        // then, so check the actual running process.
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.processName.equalsIgnoreCase("com.android.phone")) {
                phonePid = info.pid;
                phoneUid = info.uid;
                break;
            }
        }
        if (phonePid == 0) {
            throw new RuntimeException("unable to locate phone process");
        }

        if (mInjectedPid == 0 || phonePid != mInjectedPid) {
            try {
                shouldInject = !checkIfLibraryAlreadyLoaded(phonePid, libraryPath);
            } catch (IOException e) {
                Log.e(TAG, "Error trying to determine if library is loaded. Not injecting.", e);
            }
        }

        if (shouldInject) {
            prepareDexFile(phoneUid);

            Log.d(TAG, "Installing RilExtender service");

            CommandResult result = CMDProcessor.runSuCommand("logwrapper " + libraryDir + "/lib__hijack.bin__.so -d -p " + phonePid +
                    " -l " + libraryPath);

            if (!result.success()) {
                throw new RuntimeException("unable to inject phone process: " + result);
            }

            mInjectedPid = phonePid;

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                // ignore
            }

            // TODO have the service signal us when it's ready?
        } else {
            Log.e(TAG, "Library was already injected.");
        }
    }

    private boolean checkIfLibraryAlreadyLoaded(int phonePid, String libraryPath) throws IOException {
        // XXX this breaks (false negative for the library's presence) if the package manager
        // has moved our path since the lib was injected.
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
                } else if (line.contains(libraryPath)) {
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
        File rilExtenderDexCacheDir = mContext.getDir("rilextender-cache", Context.MODE_WORLD_READABLE);
        File rilExtenderDex = new File(mContext.getDir("rilextender", Context.MODE_WORLD_READABLE), "rilextender.dex");

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
            // if the file is missing, try re-building the APK and run again
            throw new RuntimeException("I/O error while extracting dex", e);
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

}
