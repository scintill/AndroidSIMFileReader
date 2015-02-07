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

import android.os.Message;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * This probably won't work well with two clients!  I don't know what happens
 * if your RIL currently uses the same AT interface.
 *
 * TODO track down SIGPIPE (apparently in "cat /dev/smd7") on uncaught exception?
 * The stack barf in logcat is bugging me, but I spent some time trying to figure it out and can't.
 */
public abstract class AtCommandTerminal {

    protected static final String TAG = "AtCommandTerminal";

    // message may be null if the response is not needed
    public abstract void send(String s, Message message);

    public abstract void dispose();

    /**
     * @return
     * @throws UnsupportedOperationException if no instance can be made
     */
    public static AtCommandTerminal factory() throws UnsupportedOperationException {
        AtCommandTerminal term = null;

        // QCom: /dev/smd7, possibly other SMD devices. On 2 devices I've checked,
        // smd7 is owned by bluetooth:bluetooth, so that could be something to sniff for if
        // it's not always smd7.
        File smdFile = new File("/dev/smd7");
        if (smdFile.exists()) {
            try {
                term = new TtyPrivFile(smdFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "IOException in constructor", e);
                // fall through
            }
        }

        if (term == null) {
            throw new UnsupportedOperationException("unable to find AT command terminal");
        }

        return term;
    }

}
