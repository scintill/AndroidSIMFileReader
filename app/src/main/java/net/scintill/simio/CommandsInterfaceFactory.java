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

import android.util.Log;

import com.SecUpwN.AIMSICD.utils.atcmd.AtCommandTerminal;

import net.scintill.simio.telephony.CommandsInterface;

public class CommandsInterfaceFactory {

    private static final String TAG = "CommandsInterfaceFactory";

    /**
     * @param appContext
     * @return
     * @throws UnsupportedOperationException if no instance can be found
     */
    public static CommandsInterface factory(android.content.Context appContext) throws UnsupportedOperationException {
        try {
            return new TelephonySeekServiceCommandsInterface(appContext);
        } catch (UnsupportedOperationException e) {
            Log.d(TAG, "SEEK not available", e);
        } catch (Exception e) {
            Log.e(TAG, "Uncaught exception from SEEK", e);
        }

        try {
            return new AtCommandInterface(AtCommandTerminal.factory());
        } catch (UnsupportedOperationException e) {
            Log.d(TAG, "AT command terminal not available", e);
        } catch (Exception e) {
            Log.e(TAG, "Uncaught exception from AT command terminal", e);
        }

        try {
            return new RilExtenderCommandsInterface(appContext);
        } catch (UnsupportedOperationException e) {
            Log.d(TAG, "RilExtender not available", e);
        } catch (Exception e) {
            Log.e(TAG, "Uncaught exception from RilExtender", e);
        }

        throw new UnsupportedOperationException("no supported SIM I/O interfaces found");
    }

}
