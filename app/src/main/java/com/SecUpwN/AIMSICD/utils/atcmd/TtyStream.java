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
package com.SecUpwN.AIMSICD.utils.atcmd;

import android.os.Handler;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

/*package*/ class TtyStream extends AtCommandTerminal {

    protected InputStream mInputStream;
    protected OutputStream mOutputStream;

    private boolean mThreadRun = true;
    private Thread mIoThread;

    /*package*/ TtyStream(InputStream in, OutputStream out) {
        mInputStream = in;
        mOutputStream = out;

        mIoThread = new Thread(new IoRunnable(), "AtCommandTerminalIO");
        mIoThread.start();
    }

    private class IoRunnable implements Runnable {
        @Override
        public void run() {
            BufferedReader in = new BufferedReader(new InputStreamReader(mInputStream));
            try {
                while (mThreadRun) {
                    // wait for something to write
                    byte[] bytesOut;
                    try {
                        bytesOut = mWriteQ.take();
                    } catch (InterruptedException e) {
                        continue; // restart loop
                    }

                    try {
                        mOutputStream.write(bytesOut);
                        mOutputStream.write('\r');
                        mOutputStream.flush();
                    } catch (IOException e) {
                        Log.e(TAG, "Output IOException", e);
                        mHandler.obtainMessage(mHandlerWhat, e).sendToTarget();
                        return; // kill thread
                    }

                    // dispatch response lines until done
                    String lineIn;
                    do {
                        try {
                            lineIn = in.readLine();
                            if (lineIn == null) throw new IOException("reader closed");
                        } catch (IOException e) {
                            Log.e(TAG, "Input IOException", e);
                            mHandler.obtainMessage(mHandlerWhat, e).sendToTarget();
                            return; // kill thread
                        }

                        if (lineIn.length() != 0) {
                            // XXX remove this logging, could have sensitive info
                            Log.d(TAG, "IO< " + lineIn);
                            if (mHandler != null) {
                                mHandler.obtainMessage(mHandlerWhat, lineIn).sendToTarget();
                            } else {
                                Log.d(TAG, "Data came in with no handler");
                            }
                        }
                        // ignore empty lines
                    } while (!(lineIn.equals("OK") || lineIn.equals("ERROR") || lineIn.startsWith("+CME ERROR")));
                }
            } finally {
                dispose();
            }
        }
    }

    @Override
    protected void sendImpl(String s, Handler handler, int what) {
        try {
            // XXX remove this logging, could have sensitive info
            Log.d(TAG, "IO> " + s);
            mWriteQ.add(s.getBytes("ASCII"));
        } catch (UnsupportedEncodingException e) {
            // we assume that if a String is being used for convenience, it must be ASCII
            throw new RuntimeException(e);
        }
    }

    @Override
    public void dispose() {
        mThreadRun = false;
    }
}
