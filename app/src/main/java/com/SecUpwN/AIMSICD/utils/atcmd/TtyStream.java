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
import java.util.ArrayList;
import java.util.List;

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

        // return result codes, return verbose codes, no local echo
        this.send("ATQ0V1E0", null, 0);
    }

    private class IoRunnable implements Runnable {
        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(mInputStream, "ASCII"));
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

                    /**
                     * ETSI TS 127 007 gives this example:
                     * <CR><LF>+CMD2: 3,0,15,"GSM"<CR><LF>
                     * <CR><LF>+CMD2: (0-3),(0,1),(0-12,15),("GSM","IRA")<CR><LF>
                     * <CR><LF>OK<CR><LF>
                     *
                     * I see embedded <CR><LF> sequences to line-break within responses.
                     * We can fake it using the BufferedReader, ignoring blank lines.
                     */

                    // dispatch response lines until done
                    String line;
                    List<String> lines = new ArrayList<>();
                    do {
                        try {
                            line = in.readLine();
                            if (line == null) throw new IOException("reader closed");
                        } catch (IOException e) {
                            Log.e(TAG, "Input IOException", e);
                            mHandler.obtainMessage(mHandlerWhat, e).sendToTarget();
                            return; // kill thread
                        }

                        if (line.length() != 0) lines.add(line);
                    	// ignore empty lines
                    } while (!(line.equals("OK") || line.equals("ERROR") || line.startsWith("+CME ERROR")));

                    // XXX remove this logging, could have sensitive info
                    int i = 0;
                    for (String s : lines) {
                        Log.d(TAG, "IO"+(i++)+"< " + s);
                    }

                    if (mHandler != null) {
                        mHandler.obtainMessage(mHandlerWhat, lines).sendToTarget();
                    } else {
                        Log.d(TAG, "Data came in with no handler");
                    }
                }
            } catch (UnsupportedEncodingException e) {
                // ASCII should work
                throw new RuntimeException(e);
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
