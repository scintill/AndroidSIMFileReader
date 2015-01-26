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

import android.util.Log;

import java.io.IOException;

/*package*/ class TtyPrivFile extends TtyStream {
    protected Process mReadProc, mWriteProc;

    /*package*/ TtyPrivFile(String ttyPath) throws IOException {
        // TODO robustify su detection?
        this(
                new ProcessBuilder("su", "-c", "\\exec cat <" + ttyPath).start(),
                new ProcessBuilder("su", "-c", "\\exec cat >" + ttyPath).start()
        );
    }

    private TtyPrivFile(Process read, Process write) {
        super(read.getInputStream(), write.getOutputStream());
        mReadProc = read;
        mWriteProc = write;

        Log.d(TAG, "mReadProc=" + mReadProc + ", mWriteProc=" + mWriteProc);
    }

    @Override
    public void dispose() {
        super.dispose();
        try {
            // Have to do this to get readproc to exit.
            // I guess it gets blocked waiting for input, so let's give it some.
            mOutputStream.write("ATE0\r".getBytes("ASCII"));
            mOutputStream.flush();
        } catch (IOException e) {
            // ignore and hope it exits
        }
        mReadProc.destroy();
        mWriteProc.destroy();
    }
}
