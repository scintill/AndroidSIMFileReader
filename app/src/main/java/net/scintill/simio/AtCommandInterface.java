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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.SecUpwN.AIMSICD.utils.atcmd.AtCommandTerminal;

import net.scintill.simio.telephony.CommandsInterface;
import net.scintill.simio.telephony.uicc.IccIoResult;
import net.scintill.simio.telephony.uicc.IccRecords;
import net.scintill.simio.telephony.uicc.IccUtils;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AtCommandInterface implements CommandsInterface {

    private static final String TAG = "AtCommandInterface";

    private AtCommandTerminal mTerminal;
    private Handler mHandler;

    private final Object mTerminalMutex = new Object();
    private ConcurrentLinkedQueue<Message> mResponseQ;

    public AtCommandInterface(AtCommandTerminal terminal) {
        mTerminal = terminal;
        mResponseQ = new ConcurrentLinkedQueue<>();
        mHandler = new ResponseHandler();
    }

    @Override
    public void iccIOForApp(int command, int fileid, String path, int p1, int p2, int p3, String data, String pin2, String aid, Message result) {
        //if (path == null) path = "";
        //if (data == null) data = "";

        synchronized (mTerminalMutex) {
            mTerminal.send("AT+CRSM="+command+","+fileid+","+p1+","+p2+","+p3/*+","+data+","+path*/, mHandler, 0);
            mResponseQ.add(result);
        }
    }

    private class ResponseHandler extends Handler {
        @Override
        public void handleMessage(Message message) {
            Message result = null;
            try {
                String line = null;
                if (message.obj instanceof String) {
                    line = ((String)message.obj).toUpperCase();

                    // ignore these and leave response queue alone
                    if (line.equals("OK")) {
                        return;
                    }
                } else if (message.obj instanceof IOException) {
                    throw (IOException)message.obj;
                }

                result = mResponseQ.remove();
                if (line.startsWith("+CRSM:")) {
                    String[] pieces = line.substring(6).split(",");
                    int sw1 = Integer.valueOf(pieces[0].trim(), 10) & 0xff;
                    int sw2 = Integer.valueOf(pieces[1].trim(), 10) & 0xff;
                    pieces[2] = pieces[2].trim();

                    String hexBody;
                    if (pieces[2].charAt(0) == '"') {
                        hexBody = pieces[2].substring(1, pieces[2].length() - 1);
                    } else {
                        hexBody = pieces[2];
                    }

                    if ((hexBody.length() % 2) != 0) {
                        throw new IOException("malformed body: " + pieces[2]);
                    }

                    // XXX remove, sensitive info
                    Log.d(TAG, "iccIO result=" + sw1 + " " + sw2 + " " + hexBody);

                    IccIoResult iccIoResult = new IccIoResult(sw1, sw2, IccUtils.hexStringToBytes(hexBody));
                    AsyncResult.forMessage(result, iccIoResult, null);
                    result.sendToTarget();
                } else if (line.equals("ERROR")) {
                    throw new IOException("AT error response");
                } else {
                    throw new IOException("unexpected response "+line);
                }
            } catch (Throwable e) {
                if (result != null) {
                    AsyncResult.forMessage(result, null, e);
                    result.sendToTarget();
                } else {
                    throw new RuntimeException(e);
                }
            }
        }
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

    @Override
    public void dispose() {
        mTerminal.dispose();
        mTerminal = null;
    }

}
