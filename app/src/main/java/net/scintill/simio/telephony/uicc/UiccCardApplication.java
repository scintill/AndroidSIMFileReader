package net.scintill.simio.telephony.uicc;

import android.os.Handler;

public interface UiccCardApplication {

    public IccCardApplicationStatus.AppType getType();

    IccFileHandler getIccFileHandler();

    IccCardApplicationStatus.AppState getState();

    String getAid();

    void registerForReady(Handler handler, int what, Object o);

    void unregisterForReady(Handler handler);

}
