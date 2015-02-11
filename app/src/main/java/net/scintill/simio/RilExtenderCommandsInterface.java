/**
 * Copyright (c) 2014 Joey Hewitt
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

import android.content.Context;
import android.os.Bundle;
import android.os.Message;
import android.os.RemoteException;

import net.scintill.rilextender.RilExtender;
import net.scintill.rilextender.RilExtenderClient;
import net.scintill.simio.telephony.CommandsInterface;
import net.scintill.simio.telephony.uicc.IccRecords;

import java.text.DateFormat;
import java.util.Date;

/**
 * A CommandsInterface that communicates with a service injected into
 * the phone process, and does that injection if it's not found.
 */
public class RilExtenderCommandsInterface implements CommandsInterface {
    protected static final String TAG = "RilExtenderCommandsInterface";

    private RilExtenderClient mClient;
    private Bundle mClientInfo;

    public RilExtenderCommandsInterface(Context context) throws UnsupportedOperationException {
        mClient = new RilExtenderClient(context);
        try {
            mClientInfo = mClient.pingSync();
        } catch (RemoteException e) {
            try {
                mClientInfo = mClient.pingSync();
            } catch (RemoteException e2) {
                throw new UnsupportedOperationException("unable to initialize RilExtender", e);
            }
        }
    }

    @Override
    public void iccIOForApp(int command, int fileid, String path, int p1, int p2, int p3, String data, String pin2, String aid, Message
            result) {
        mClient.iccIOForApp(command, fileid, path, p1, p2, p3, data, pin2, aid, result);
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
        // empty
    }

    @Override
    public String getInterfaceDebugInfo() {
        String s = "Service injection date=";
        s += DateFormat.getDateTimeInstance().format(new Date(mClientInfo.getLong("birthdate")))+"\n";
        s += "Loaded service version=";
        s += mClientInfo.getInt("version")+"\n";
        s += "Bundled service version=" + RilExtender.VERSION;
        return s;
    }
}
