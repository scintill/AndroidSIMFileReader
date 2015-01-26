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

import java.io.File;
import java.io.IOException;
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

    /*package*/ AtCommandTerminal() {
        mWriteQ = new LinkedBlockingQueue<>();
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
                term = new TtyPrivFile(smdFile.getAbsolutePath());
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
