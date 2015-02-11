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
import android.content.ComponentName;
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

    public RilExtenderClient(Context context) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
            throw new UnsupportedOperationException("RilExtender probably won't work with Lollipop"+
                    " (more accurately, the ART runtime)");
        }

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
                startServiceAndWait();
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

    private void startServiceAndWait() {
        Log.d(TAG, "Starting RilExtender service");

        final Message signal = Message.obtain();
        signal.obj = null;
        HandlerThread handlerThread = new HandlerThread("installServiceAndWait.RilExtenderClient");
        handlerThread.start();
        final Handler handler = new Handler(handlerThread.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                mContext.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        synchronized (signal) {
                            signal.obj = new Boolean(true);
                            signal.notifyAll();
                        }
                    }
                }, new IntentFilter("net.scintill.rilextender.I_AM_RILEXTENDER"), null, handler);

                Intent intent = new Intent();
                intent.setComponent(new ComponentName("net.scintill.rilextender", "net.scintill.rilextender.RilExtenderInstaller"));
                mContext.startService(intent);
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
    }

}
