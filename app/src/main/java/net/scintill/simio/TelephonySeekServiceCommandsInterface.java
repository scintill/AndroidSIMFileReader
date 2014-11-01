package net.scintill.simio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.AsyncResult;
import android.os.Message;
import android.os.Parcel;
import android.telephony.TelephonyManager;
import android.util.Log;

import net.scintill.simio.telephony.CommandsInterface;
import net.scintill.simio.telephony.uicc.IccIoResult;
import net.scintill.simio.telephony.uicc.IccRecords;
import net.scintill.simio.telephony.uicc.IccUtils;

import com.SecUpwN.AIMSICD.utils.CMDProcessor;
import com.SecUpwN.AIMSICD.utils.CommandResult;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;

public class TelephonySeekServiceCommandsInterface implements CommandsInterface {
    protected static final String TAG = "FakeRil";

    private TelephonyManager mTelephonyManager;
    private int mTRANSACTION_transmitIccSimIO;
    private int mSeekUid;

    public TelephonySeekServiceCommandsInterface(Context context) {
        // Get transmitIccSimIO service call ID
        try {
            Field f = Class.forName("com.android.internal.telephony.ITelephony$Stub").getDeclaredField("TRANSACTION_transmitIccSimIO");
            f.setAccessible(true);
            mTRANSACTION_transmitIccSimIO = f.getInt(null);
        } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            throw new RuntimeException("could not get com.android.internal.telephony.ITelephony.Stub.TRANSACTION_transmitIccSimIO -- the telephony service probably doesn't support SEEK");
        }

        // Get OpenMobile Service's user ID, or NFC id
        try {
            mSeekUid = context.getPackageManager().getApplicationInfo("org.simalliance.openmobileapi.service", PackageManager.GET_UNINSTALLED_PACKAGES).uid;
        } catch (PackageManager.NameNotFoundException e) {
            // CyanogenMod 11 currently doesn't have the service, but it does have the service call, granted to NFC user
            try {
                // this is not a public part of the API, but should be a public field
                Field f = Class.forName("android.os.Process").getField("NFC_UID");
                mSeekUid = f.getInt(null);
            } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e2) {
                throw new RuntimeException("could not get android.os.Process.NFC_UID", e2);
            }
        }

        //SEService ses = new SEService(context, this);
    }

    @Override
    public void iccIOForApp(int command, int fileid, String path, int p1, int p2, int p3, String data, String pin2, String aid, Message
            result) {
        Log.d(TAG, "iccIO "+command + " " + fileid + " " + path + " " + p1 + " " + p2 + " " + p3 + " " + data + " " + pin2 + " " + aid + " " + result);

        /**
         * Use the "service" command-line utility, elevated to a privileged user, to invoke a Binder call to the SEEK endpoint
         * in the telephony service.  The only access check it does is that the UID is allowed, so we bypass the access
         * checks implemented by the OpenMobile service.
         */

        CommandResult cmdResult = CMDProcessor.runSuCommand(Integer.toString(mSeekUid),
                "service call phone " + mTRANSACTION_transmitIccSimIO +
                " i32 " + fileid + " i32 " + command + " i32 " + p1 + " i32 " + p2 + " i32 " + p3 + " s16 " + path); // XXX shell escaping?
        if (!cmdResult.success()) {
            throw new RuntimeException("privileged service call failed");
        }

        // this is ugly. I'd like to forgo it altogether and find a way to call through binder as the privileged user in Java.
        // I'm considering copying the OpenMobile service to this project, patching out the access checks, and starting it up as the privileged user,
        // then driving it through the standard OpenMobile classes.  Sounds like a lot of work that may end up being impossible, or not much nicer than this.
        String asciiHex = cmdResult.getStdout(); // something like Parcel(\n    0x0000: 00000000 00000000 00000000 00000000 'asdfasfdadsfasdf'\n...
        Log.d(TAG, "service result="+asciiHex);
        String[] lines = asciiHex.split("\\n");
        StringBuffer fixedBytesAsciiHex = new StringBuffer();
        for (int i = 1; i < lines.length; i++) {
            String[] lineReversedWords = lines[i].substring(lines[i].indexOf(':')+2, lines[i].indexOf('\'')-1).trim().split(" ");
            // https://github.com/CyanogenMod/android_frameworks_native/blob/bf9b6621f64bff02fc94bc1b82b045d2ad64659b/libs/binder/Debug.cpp#L223
            // not sure why, but OK... this is way too dependent on the system for my taste, even in a hack like this...
            for (String lineReversedWord : lineReversedWords) {
                fixedBytesAsciiHex.append(lineReversedWord.substring(6, 8))
                         .append(lineReversedWord.substring(4,6))
                         .append(lineReversedWord.substring(2,4))
                         .append(lineReversedWord.substring(0, 2));
            }
        }

        byte[] bytes = IccUtils.hexStringToBytes(fixedBytesAsciiHex.toString());

        // unwrap the Binder parcel
        Parcel p = Parcel.obtain();
        p.unmarshall(bytes, 0, bytes.length);
        p.setDataPosition(0); // I owe you one, StackOverflow person! http://stackoverflow.com/a/18000094
        p.readException();
        bytes = p.createByteArray();
        p.recycle();

        if (bytes == null) {
            throw new IllegalArgumentException("error parsing parcel");
        }

        // strip off sw1 and sw2
        int sw1 = bytes[bytes.length-2] & 0xff;
        int sw2 = bytes[bytes.length-1] & 0xff;
        bytes = Arrays.copyOf(bytes, bytes.length-2);

        Log.d(TAG, "iccIO parsedResult="+sw1+" "+sw2+" "+IccUtils.bytesToHexString(bytes));

        IccIoResult iccIoResult = new IccIoResult(sw1, sw2, bytes);

        AsyncResult.forMessage(result, iccIoResult, null);
        result.sendToTarget();
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
