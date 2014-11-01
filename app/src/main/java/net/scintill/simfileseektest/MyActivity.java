package net.scintill.simfileseektest;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import com.SecUpwN.AIMSICD.utils.Helpers;

import net.scintill.simio.CardApplication;
import net.scintill.simio.TelephonySeekServiceCommandsInterface;
import net.scintill.simio.telephony.CommandsInterface;
import net.scintill.simio.telephony.uicc.IccUtils;
import net.scintill.simio.telephony.uicc.SIMRecords;
import net.scintill.simio.telephony.uicc.UiccCardApplication;


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
    private UiccCardApplication mCardApplication;

    private void filesTests() {
        try {
            if (Helpers.checkSu()) {
                // XXX use proper implementation depending on card type
                // XXX get app ID?
                // TODO add other implementations of CommandsInterface that use different SIM I/O methods,
                // and a factory-type class that determines the best one and creates that instance
                mCommandsInterface = new TelephonySeekServiceCommandsInterface(this.getApplicationContext());
                mCardApplication = new CardApplication(mCommandsInterface);
                final SIMRecords records = new SIMRecords(mCardApplication, this.getApplicationContext(), mCommandsInterface);

                records.registerForRecordsLoaded(new Handler(new Handler.Callback() {
                    @Override
                    public boolean handleMessage(Message message) {
                        Log.d(TAG, "MSISDN=" + records.getMsisdnNumber());
                        Log.d(TAG, "TMSI=" + IccUtils.bytesToHexString(records.getTemporaryMobileSubscriberIdentity()));
                        Log.d(TAG, "LAI=" + IccUtils.bytesToHexString(records.getLocationAreaInformation()));
                        return true;
                    }
                }), 0, null);
            }
            // checkSu() logs if su wasn't available
        } catch (Throwable t) {
            Log.e(TAG, "Error loading SIM files", t);
        }
    }
}
