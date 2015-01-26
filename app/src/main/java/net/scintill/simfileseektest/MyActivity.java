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
package net.scintill.simfileseektest;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import com.SecUpwN.AIMSICD.utils.AtCommandTerminal;
import com.SecUpwN.AIMSICD.utils.Helpers;

import net.scintill.simio.AtCommandInterface;
import net.scintill.simio.CardApplication;
import net.scintill.simio.RilExtender;
import net.scintill.simio.RilExtenderCommandsInterface;
import net.scintill.simio.TelephonySeekServiceCommandsInterface;
import net.scintill.simio.telephony.CommandsInterface;
import net.scintill.simio.telephony.uicc.IccUtils;
import net.scintill.simio.telephony.uicc.SIMRecords;
import net.scintill.simio.telephony.uicc.UiccCardApplication;

import java.text.DateFormat;
import java.util.Date;


public class MyActivity extends Activity {

    private final static String TAG = "SIMFileSeekTest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        // do files test in another thread, so the UI doesn't get blocked
        HandlerThread handlerThread = new HandlerThread("SIMFilesTest");
        handlerThread.start();
        // getLooper() can block, but it shouldn't be as bad as the blocking on SuperSU before.
        // Maybe I'm doing something wrong here, though.
        new Handler(handlerThread.getLooper()).post(new Runnable() {
            @Override
            public void run() {
                filesTests();
            }
        });
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.my, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private CommandsInterface mCommandsInterface;

    private void filesTests() {
        try {
            if (Helpers.checkSu()) {
                // XXX use proper implementation depending on card type
                // XXX get app ID?
                // TODO add other implementations of CommandsInterface that use different SIM I/O methods,
                // and a factory-type class that determines the best one and creates that instance
                //final CommandsInterface commandsInterface = new TelephonySeekServiceCommandsInterface(this.getApplicationContext());
                //final RilExtenderCommandsInterface commandsInterface = new RilExtenderCommandsInterface(this.getApplicationContext());
                mCommandsInterface = new AtCommandInterface(AtCommandTerminal.factory());
                UiccCardApplication cardApplication = new CardApplication(mCommandsInterface);
                final SIMRecords records = new SIMRecords(cardApplication, this.getApplicationContext(), mCommandsInterface);

                records.registerForRecordsLoaded(new Handler() {
                    @Override
                    public void handleMessage(Message message) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                String results =
                                    "MSISDN=" + records.getMsisdnNumber()+"\n"+
                                    "TMSI=" + IccUtils.bytesToHexString(records.getTemporaryMobileSubscriberIdentity())+"\n"+
                                    "LAI=" + IccUtils.bytesToHexString(records.getLocationAreaInformation())+"\n";/*+
                                    "\n"+
                                    "Service injection date=";
                                try {
                                    results += DateFormat.getDateTimeInstance().format(new Date(commandsInterface.getServiceBirthDate()))+"\n";
                                } catch (RemoteException e) {
                                    results += "(unknown)\n";
                                }
                                results += "Loaded service version=";
                                try {
                                    results += commandsInterface.getServiceVersion()+"\n";
                                } catch (RemoteException e) {
                                    results += "(unknown)\n";
                                }
                                results += "Bundled service version=" + RilExtender.VERSION;*/

                                TextView textViewResults = (TextView)MyActivity.this.findViewById(R.id.results);
                                textViewResults.setText(results);
                            }
                        });
                    }
                }, 0, null);
            } else {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        // technically we can work without su, if the phone process has already been injected
                        TextView textViewResults = (TextView) MyActivity.this.findViewById(R.id.results);
                        textViewResults.setText("Superuser/su not available!");
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error loading SIM files", t);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mCommandsInterface.dispose();
    }
}
