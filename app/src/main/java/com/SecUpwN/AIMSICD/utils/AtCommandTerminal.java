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
 * TODO combine threads?
 * They end up running serially anyway because the BP misses things if they're written while it's busy
 * (at least on Qcom)
 * TODO track down SIGPIPE (apparently in "cat /dev/smd7") on uncaught exception?
 * The stack barf in logcat is bugging me, but I spent some time trying to figure it out and can't.
 */
public abstract class AtCommandTerminal {

    protected static final String TAG = "AtCommandTerminal";

    protected Handler mHandler;
    protected int mHandlerWhat;

    protected BlockingQueue<byte[]> mWriterQ;
    protected Object mWriterFlag = new Object();

    private static class Tty extends AtCommandTerminal {
        protected boolean mThreadsRun = true;
        protected Thread mReadThread, mWriteThread;
        protected Process mReadProc, mWriteProc;

        protected Tty(String ttyPath) throws IOException {
            // TODO robustify su detection?
            mReadProc = new ProcessBuilder("su", "-c", "exec \\cat "+ ttyPath).redirectErrorStream(true).start();
            mWriteProc = new ProcessBuilder("su", "-c", "exec \\cat >" + ttyPath).start();

            mReadProc.getOutputStream().close();
            mWriteProc.getInputStream().close();

            Log.d(TAG, "mReadProc="+ mReadProc +", mWriteProc="+ mWriteProc);

            mWriterQ = new LinkedBlockingQueue<>();

            mReadThread = new Thread(new ReaderRunnable(), "AtCommandTerminalRead");
            mReadThread.start();
            mWriteThread = new Thread(new WriterRunnable(), "AtCommandTerminalWrite");
            mWriteThread.start();
        }

        private class ReaderRunnable implements Runnable {
            @Override
            public void run() {
                BufferedReader br = new BufferedReader(new InputStreamReader(mReadProc.getInputStream()));
                try {
                    while (mThreadsRun) {
                        try {
                            int exitValue = mReadProc.exitValue();
                            Log.e(TAG, "readProc exited: " + exitValue);
                            mThreadsRun = false;
                            break;
                        } catch (IllegalThreadStateException e) {
                            // means it's still running, ignore
                        }

                        String line = br.readLine();
                        if (line == null) {
                            throw new RuntimeException("reader closed");
                        }

                        if (line.equals("OK") || line.equals("ERROR") || line.startsWith("+CME ERROR")) {
                            synchronized (mWriterFlag) {
                                mWriterFlag.notify();
                            }
                        }

                        if (line.length() != 0) {
                            // XXX remove this logging, could have sensitive info
                            Log.d(TAG, "IO< " + line);
                            if (mHandler != null) {
                                mHandler.obtainMessage(mHandlerWhat, line).sendToTarget();
                            } else {
                                Log.d(TAG, "No handler for incoming data");
                            }
                        }
                        // ignore empty lines
                    }
                } catch (IOException e) {
                    // XXX better way?
                    throw new RuntimeException(e);
                } finally {
                    mReadProc.destroy();
                }
            }
        }

        private class WriterRunnable implements Runnable {
            @Override
            public void run() {
                OutputStream os = mWriteProc.getOutputStream();
                try {
                    while (mThreadsRun) {
                        try {
                            int exitValue = mWriteProc.exitValue();
                            Log.e(TAG, "writeProc exited: " + exitValue);
                            mThreadsRun = false;
                            break;
                        } catch (IllegalThreadStateException e) {
                            // means it's still running, ignore
                        }

                        try {
                            byte[] item = mWriterQ.take();
                            os.write(item);
                            os.write('\r');
                            os.flush();
                        } catch (InterruptedException e) {
                            continue;
                        } catch (IOException e) {
                            Log.e(TAG, "IOException", e);
                            mHandler.obtainMessage(mHandlerWhat, e).sendToTarget();
                        }

                        // wait until we can write again
                        synchronized (mWriterFlag) {
                            for (; ; ) {
                                try {
                                    mWriterFlag.wait();
                                    break;
                                } catch (InterruptedException e) {
                                    // fall through, keep waiting
                                }
                            }
                        }
                    }
                } finally {
                    mWriteProc.destroy();
                }
            }
        }

        @Override
        protected void sendImpl(String s, Handler handler, int what) {
            try {
                // XXX remove this logging, could have sensitive info
                Log.d(TAG, "IO> " + s+" "+Thread.currentThread()
                );
                mWriterQ.add(s.getBytes("ASCII"));
            } catch (UnsupportedEncodingException e) {
                // we assume that if a String is being used for convenience, it must be ASCII
                throw new RuntimeException(e);
            }
        }
    }

    public void send(String s, Handler handler, int what) {
        mHandler = handler;
        mHandlerWhat = what;
        sendImpl(s, handler, what);
    }

    protected abstract void sendImpl(String s, Handler handler, int what);

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

        // turn off local echo
        //if (term != null) term.send("ATE0", null, 0);

        return term;
    }

}
