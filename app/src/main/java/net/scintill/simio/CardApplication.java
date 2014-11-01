package net.scintill.simio;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Registrant;

import net.scintill.simio.telephony.CommandsInterface;
import net.scintill.simio.telephony.uicc.IccCardApplicationStatus;
import net.scintill.simio.telephony.uicc.IccFileHandler;
import net.scintill.simio.telephony.uicc.SIMFileHandler;
import net.scintill.simio.telephony.uicc.UiccCardApplication;

public class CardApplication implements UiccCardApplication {

    private CommandsInterface mCi;

    public CardApplication(CommandsInterface ci) {
        this.mCi = ci;
    }

    @Override
    public IccCardApplicationStatus.AppType getType() {
        return IccCardApplicationStatus.AppType.APPTYPE_SIM;
    }

    private IccFileHandler mFh;

    @Override
    public IccFileHandler getIccFileHandler() {
        if (mFh == null) {
            mFh = new SIMFileHandler(this, getAid(), mCi);
        }
        return mFh;
    }

    @Override
    public IccCardApplicationStatus.AppState getState() {
        return IccCardApplicationStatus.AppState.APPSTATE_READY;
    }

    @Override
    public String getAid() {
        return null;
    }

    @Override
    public void registerForReady(Handler handler, int what, Object o) {
        new Registrant(handler, what, o).notifyRegistrant(new AsyncResult(null, null, null));
    }

    @Override
    public void unregisterForReady(Handler handler) {
    }

}
