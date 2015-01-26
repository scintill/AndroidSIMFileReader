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
package com.SecUpwN.AIMSICD.utils;

import android.os.Handler;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This probably won't work well with two clients!  I don't know what happens
 * if your RIL currently uses the same AT interface.
 *
 * TODO track down SIGPIPE (apparently in "cat /dev/smd7") on uncaught exception?
 * The stack barf in logcat is bugging me, but I spent some time trying to figure it out and can't.
 */
public abstract class AtCommandTerminal {

    protected static final String TAG = "AtCommandTerminal";

    protected Handler mHandler;
    protected int mHandlerWhat;

    protected BlockingQueue<byte[]> mWriteQ;

    private static class Tty extends AtCommandTerminal {
        protected boolean mThreadRun = true;
        protected Thread mIoThread;
        protected Process mReadProc, mWriteProc;

        protected Tty(String ttyPath) throws IOException {
            // TODO robustify su detection?
            mReadProc = new ProcessBuilder("su", "-c", "\\exec cat <"+ ttyPath).start();
            mWriteProc = new ProcessBuilder("su", "-c", "\\exec cat >" + ttyPath).start();

            mReadProc.getOutputStream().close();
            mWriteProc.getInputStream().close();

            Log.d(TAG, "mReadProc="+ mReadProc +", mWriteProc="+ mWriteProc);

            mWriteQ = new LinkedBlockingQueue<>();

            mIoThread = new Thread(new IoRunnable(), "AtCommandTerminalIO");
            mIoThread.start();
        }

        private class IoRunnable implements Runnable {
            @Override
            public void run() {
                BufferedReader in = new BufferedReader(new InputStreamReader(mReadProc.getInputStream()));
                OutputStream out = mWriteProc.getOutputStream();
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
                            out.write(bytesOut);
                            out.write('\r');
                            out.flush();
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
                    mReadProc.destroy();
                    mWriteProc.destroy();
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
            try {
                // Have to do this to get readproc to exit.
                // I guess it gets blocked waiting for input, so let's give it some.
                mWriteProc.getOutputStream().write("ATE0\r".getBytes("ASCII"));
                mWriteProc.getOutputStream().flush();
            } catch (IOException e) {
                // ignore and hope it exits
            }
            mReadProc.destroy();
            mWriteProc.destroy();
        }

    }

    public void send(String s, Handler handler, int what) {
        mHandler = handler;
        mHandlerWhat = what;
        sendImpl(s, handler, what);
    }

    protected abstract void sendImpl(String s, Handler handler, int what);

    public abstract void dispose();

    public void forgetHandler() {
        mHandler = null;
    }

    public static AtCommandTerminal factory() {
        AtCommandTerminal term = null;

        File smdFile = new File("/dev/smd7");
        if (smdFile.exists()) {
            try {
                term = new Tty(smdFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "IOException in constructor", e);
                // fall through
            }
        }

        // return result codes, return verbose codes, no local echo
        if (term != null) term.send("ATQ0V1E0", null, 0);

        return term;
    }

}
