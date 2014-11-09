/**
 * Copyright (c) 2014 Joey Hewitt
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
import android.content.Context;
import android.os.AsyncResult;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.SecUpwN.AIMSICD.utils.CMDProcessor;
import com.SecUpwN.AIMSICD.utils.CommandResult;

import net.scintill.simio.telephony.CommandsInterface;
import net.scintill.simio.telephony.uicc.IccIoResult;
import net.scintill.simio.telephony.uicc.IccRecords;
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
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/**
 * A CommandsInterface that communicates with a new Binder service injected into
 * the phone process, and does that injection if it's not found.
 *
 * There are some things that are probably not thread-safe, as for now it's synchronous
 * and single-threaded.
 */
public class RilExtenderCommandsInterface implements CommandsInterface {
    protected static final String TAG = "RilExtenderCommandsInterface";

    private IRilExtender mIRilExtender;
    private Context mContext;
    private boolean mInjectedLibrary;

    public RilExtenderCommandsInterface(Context context) {
        mInjectedLibrary = false;
        mContext = context;
        mIRilExtender = getIRilExtender();

        try {
            if (mIRilExtender.pingRilExtender()) {
                Log.i(TAG, "Service is up");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Service is not responding");
        } catch (SecurityException e) {
            checkSecurityException(e);
        }
    }

    @Override
    public void iccIOForApp(int command, int fileid, String path, int p1, int p2, int p3, String data, String pin2, String aid, Message
            result) {
        iccIOForApp(command, fileid, path, p1, p2, p3, data, pin2, aid, result, false);
    }

    private void iccIOForApp(int command, int fileid, String path, int p1, int p2, int p3, String data, String pin2, String aid, Message
            result, boolean recurse) {
        Log.d(TAG, "iccIO " + command + " " + fileid + " " + path + " " + p1 + " " + p2 + " " + p3 + " " + data + " " + pin2 + " " + aid + " " + result);

        byte[] payloadBytes;
        IccIoResult iccIoResult = null;
        try {
            payloadBytes = mIRilExtender.iccIOForApp(command, fileid, path, p1, p2, p3, data, pin2, aid);
            int sw1 = payloadBytes[0] & 0xff;
            int sw2 = payloadBytes[1] & 0xff;
            payloadBytes = Arrays.copyOfRange(payloadBytes, 2, payloadBytes.length);

            Log.d(TAG, "iccIO result=" + sw1 + " " + sw2 + " " + IccUtils.bytesToHexString(payloadBytes));

            iccIoResult = new IccIoResult(sw1, sw2, payloadBytes);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException", e);
            // fall through to return null
        } catch (SecurityException e) {
            if (checkSecurityException(e) && !recurse) {
                // install service and try again
                installServiceAndWait();
                iccIOForApp(command, fileid, path, p1, p2, p3, data, pin2, aid, result, true);
                return;
            }
            // fall through to return null
        }

        AsyncResult.forMessage(result, iccIoResult, null);
        result.sendToTarget();
    }

    private IRilExtender getIRilExtender() {
        try {
            return IRilExtender.Stub.asInterface((IBinder)
                    Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, Context.TELEPHONY_SERVICE)
            );
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | ClassNotFoundException e) {
            throw new RuntimeException("Error getting service", e);
        }
    }

    private boolean checkSecurityException(SecurityException e) {
        if (e.getMessage().equals("Binder invocation to an incorrect interface")) {
            Log.e(TAG, "RilExtender service is not responding");
            return true;
        } else {
            Log.e(TAG, "SecurityException", e);
            return false;
        }
    }

    private void installServiceAndWait() {
        boolean shouldInject = false;
        int phonePid = 0;
        int phoneUid = 0;
        String libraryDir = mContext.getApplicationInfo().nativeLibraryDir;
        String libraryPath = libraryDir + "/librilinject.so";

        // We'll remember if we've recently injected it, but maybe the app has restarted since
        // then, so check the actual running process.
        if (!mInjectedLibrary) {
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

            mInjectedLibrary = true;
        } else {
            Log.e(TAG, "Library was already injected. Waiting to see if it activates.");
        }

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            // ignore
        }

        // TODO have the service signal us when it's ready?
    }

    private boolean checkIfLibraryAlreadyLoaded(int phonePid, String libraryPath) throws IOException {
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

        if (rilExtenderDex.getAbsolutePath().equals("/data/data/net.scintill.simfileseektest/app_rilextender/rilextender.dex") == false) {
            throw new RuntimeException("The dex wasn't placed where the hardcoded NDK injector expects it! Path was "+rilExtenderDex.getAbsolutePath());
            // We could probably have the NDK injector check several paths if this is a problem.
        }
        if (rilExtenderDexCacheDir.getAbsolutePath().equals("/data/data/net.scintill.simfileseektest/app_rilextender-cache") == false) {
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
        CMDProcessor.runSuCommand("chown "+phoneUid+":"+phoneUid+" /data/data/net.scintill.simfileseektest/app_rilextender-cache");
    }

    @Override
    public void registerForIccRefresh(IccRecords iccRecords, int eventRefresh, Object o) {
    }

    @Override
    public void unregisterForIccRefresh(IccRecords iccRecords) {
    }

    @Override
    public void getCDMASubscription(Message message) {
        throw new RuntimeException("unimplemented");
    }

    @Override
    public void getIMSIForApp(String aid, Message message) {
        throw new RuntimeException("unimplemented");
    }

    @Override
    public void setRadioPower(boolean b, Object o) {
        throw new RuntimeException("unimplemented");
    }
}
