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

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.NoSuchElementException;

/**
 * This class is injected into the com.android.phone process.
 * The static function onPhoneServiceTransact() gets called
 * when the phone service's onTransact() gets called.
 * We can then handle new service calls.
 */
public class RilExtender extends IRilExtender.Stub {
    private static final String TAG = "RilExtender";
    private static final String DESCRIPTOR = IRilExtender.class.getName();
    public static final int VERSION = 2;

    public static boolean onPhoneServiceTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        //Log.d(TAG, "onTransact " + code + " " + android.os.Process.myPid());
        switch(code) {
            case TRANSACTION_iccIOForApp:
            case TRANSACTION_pingRilExtender:
            case TRANSACTION_getBirthDate:
            case TRANSACTION_getVersion:
                // Our transaction codes overlap with the original phone service -- is
                // the caller trying to reach us?
                data.readInt();
                String descriptor = data.readString();
                data.setDataPosition(0);
                if (descriptor.equals(DESCRIPTOR)) {
                    RilExtender instance = getInstance();
                    if (instance.accessCheck()) {
                        return instance.onTransact(code, data, reply, flags);
                    } else {
                        Log.d(TAG, "access check failed, ignoring call");
                    }
                }
                // fall through
            default:
                return false;
        }
    }

    public byte[] iccIOForApp(int command, int fileId, String path, int p1, int p2, int p3, String data, String pin2, String aid) {
        try {
            return iccIOForAppImpl(command, fileId, path, p1, p2, p3, data, pin2, aid);
        } catch (Exception e) {
            // Binder doesn't propagate exceptions
            Log.e(TAG, "iccIOForApp exception", e);
            return null;
        }
    }

    private byte[] iccIOForAppImpl(final int command, final int fileId, final String path, final int p1, final int p2, final int p3, final String data, final String pin2, final String aid) {
        Log.d(TAG, "simIo "+fileId+" "+command+" "+p1+" "+p2+" "+p3+" "+path);

        final Message receivedMessage = Message.obtain();
        try {

            final Handler syncHandler = new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(Message message) {
                    try {
                        synchronized (receivedMessage) {
                            receivedMessage.copyFrom(message);
                            receivedMessage.notify();
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "handleMessage() threw an exception", t);
                    }
                }
            };

            // This has to be done on the main thread.
            boolean postResult = new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Get the RIL object.
                        Object gsmPhone = getGsmPhone();
                        Field f = null;
                        try {
                            f = gsmPhone.getClass().getField("mCi");
                        } catch (NoSuchFieldException e) {
                            try {
                                f = gsmPhone.getClass().getField("mCM");
                            } catch (NoSuchFieldException e2) { /* fall through */ }
                        }
                        if (f == null) throw new RuntimeException("can't find CommandsInterface");

                        Object RIL = f.get(gsmPhone);

                        RIL.getClass().getMethod("iccIOForApp",
                                int.class, int.class, String.class, int.class, int.class,
                                int.class, String.class, String.class, String.class,
                                Message.class)

                                .invoke(RIL, command, fileId, path, p1, p2, p3, data, pin2, aid, syncHandler.obtainMessage());
                    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            if (!postResult) {
                throw new RuntimeException("failed to post to main thread");
            }

            boolean done = false;
            do {
                try {
                    synchronized (receivedMessage) {
                        receivedMessage.wait(10000); // timeout, don't want to hang forever
                    }
                    done = true;
                } catch (InterruptedException e) {
                    // keep waiting
                }
            } while (!done);

            // exception handling based on IccFileHandler#processException()
            Object asyncResult = receivedMessage.obj;
            Throwable asyncResultException = (Throwable) asyncResult.getClass().getField("exception").get(asyncResult);
            if (asyncResultException != null) throw new RuntimeException("asyncResult.exception", asyncResultException);

            Object iccIoResult = asyncResult.getClass().getField("result").get(asyncResult);
            Class iccIoResultClass = iccIoResult.getClass();
            Throwable iccIoException = (Throwable) iccIoResultClass.getMethod("getException").invoke(iccIoResult);
            if (iccIoException != null) throw new RuntimeException("iccIoResult.getException()", iccIoException);

            int sw1 = iccIoResultClass.getField("sw1").getInt(iccIoResult);
            int sw2 = iccIoResultClass.getField("sw2").getInt(iccIoResult);
            byte[] payload = (byte[]) iccIoResultClass.getField("payload").get(iccIoResult);

            byte[] fullResponse = new byte[payload.length+2];
            fullResponse[0] = (byte)(sw1 & 0xff);
            fullResponse[1] = (byte)(sw2 & 0xff);
            System.arraycopy(payload, 0, fullResponse, 2, payload.length);

            return fullResponse;
        } catch (IllegalAccessException | NoSuchFieldException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException(e);
        } finally {
            receivedMessage.recycle();
        }
    }

    private boolean accessCheck() {
        // See if caller's UID belongs to our app.
        // There could be multiple apps sharing a UID, but as far as I can tell
        // that's only done with their consent.

        PackageManager pm = mContext.getPackageManager();
        for (String pkg : pm.getPackagesForUid(Binder.getCallingUid())) {
            if (pkg.equals("net.scintill.simfileseektest")) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean pingRilExtender() throws RemoteException {
        return true;
    }

    @Override
    public long getBirthDate() { return mBirthDate; }

    @Override
    public int getVersion() { return VERSION; }

    private static RilExtender instance;
    private static RilExtender getInstance() {
        if (instance == null) {
            instance = new RilExtender();
        }
        return instance;
    }

    /**
     * Must be done on main thread
     * @return The GSMPhone instance.
     */
    private static Object getGsmPhone() {
        // Adapted from frameworks/opt/telephony/src/java/com/android/internal/telephony/DebugService.java
        try {
            Object phoneProxy = Class.forName("com.android.internal.telephony.PhoneFactory").getMethod("getDefaultPhone").invoke(null);
            Object gsmPhone = phoneProxy.getClass().getMethod("getActivePhone").invoke(phoneProxy);
            return gsmPhone;
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private Context mContext;
    private long mBirthDate;

    private RilExtender() {
        final Object oSignal = new Object();

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object gsmPhone = getGsmPhone(); // this has to be done on the main thread
                    Context phoneContext = (Context) gsmPhone.getClass().getMethod("getContext").invoke(gsmPhone);
                    mContext = (Context) phoneContext.getClass().getMethod("getApplicationContext").invoke(phoneContext);
                    synchronized (oSignal) {
                        oSignal.notify();
                    }
                } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                    Log.e(TAG, "Error finding context", e);
                }
            }
        });

        try {
            synchronized (oSignal) {
                oSignal.wait(5000);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Error finding context", e);
        }

        mBirthDate = System.currentTimeMillis();
    }
}
