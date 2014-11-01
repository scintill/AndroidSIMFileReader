/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.scintill.simio.telephony.uicc;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;

import net.scintill.simio.telephony.CommandsInterface;
import net.scintill.simio.telephony.uicc.IccCardApplicationStatus.AppState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@hide}
 */
public abstract class IccRecords extends Handler implements IccConstants {
    protected static final boolean DBG = true;

    // ***** Instance Variables
    protected AtomicBoolean mDestroyed = new AtomicBoolean(false);
    protected Context mContext;
    protected CommandsInterface mCi;
    protected IccFileHandler mFh;
    protected UiccCardApplication mParentApp;

    protected RegistrantList mRecordsLoadedRegistrants = new RegistrantList();
    protected RegistrantList mImsiReadyRegistrants = new RegistrantList();
    protected RegistrantList mRecordsEventsRegistrants = new RegistrantList();
    protected RegistrantList mNewSmsRegistrants = new RegistrantList();
    protected RegistrantList mNetworkSelectionModeAutomaticRegistrants = new RegistrantList();

    protected int mRecordsToLoad;  // number of pending load requests

    // ***** Cached SIM State; cleared on channel close

    protected boolean mRecordsRequested = false; // true if we've made requests for the sim records

    protected String mIccId;
    protected String mMsisdn = null;  // My mobile number
    protected String mMsisdnTag = null;
    protected String mVoiceMailNum = null;
    protected String mVoiceMailTag = null;
    protected String mNewVoiceMailNum = null;
    protected String mNewVoiceMailTag = null;
    protected boolean mIsVoiceMailFixed = false;
    protected String mImsi;

    protected int mMncLength = UNINITIALIZED;
    protected int mMailboxIndex = 0; // 0 is no mailbox dailing number associated

    protected int mSmsCountOnIcc = -1;

    protected String mSpn;

    protected String mGid1;

    // ***** Constants

    // Markers for mncLength
    protected static final int UNINITIALIZED = -1;
    protected static final int UNKNOWN = 0;

    // Bitmasks for SPN display rules.
    public static final int SPN_RULE_SHOW_SPN  = 0x01;
    public static final int SPN_RULE_SHOW_PLMN = 0x02;

    // ***** Event Constants
    protected static final int EVENT_SET_MSISDN_DONE = 30;
    public static final int EVENT_CFI = 0; // Call Forwarding indication
    public static final int EVENT_SPN = 1; // Service Provider Name


    public static final int EVENT_GET_ICC_RECORD_DONE = 100;
    public static final int EVENT_REFRESH = 31; // ICC refresh occurred
    protected static final int EVENT_APP_READY = 1;

    protected static final int EVENT_GET_SMS_RECORD_SIZE_DONE = 35;

    public String toString() {
        return "mDestroyed=" + mDestroyed
                + " mContext=" + mContext
                + " mCi=" + mCi
                + " mFh=" + mFh
                + " mParentApp=" + mParentApp
                + " recordsLoadedRegistrants=" + mRecordsLoadedRegistrants
                + " mImsiReadyRegistrants=" + mImsiReadyRegistrants
                + " mRecordsEventsRegistrants=" + mRecordsEventsRegistrants
                + " mNewSmsRegistrants=" + mNewSmsRegistrants
                + " mNetworkSelectionModeAutomaticRegistrants="
                        + mNetworkSelectionModeAutomaticRegistrants
                + " recordsToLoad=" + mRecordsToLoad
                + " recordsRequested=" + mRecordsRequested
                + " iccid=" + mIccId
                + " msisdn=" + mMsisdn
                + " msisdnTag=" + mMsisdnTag
                + " voiceMailNum=" + mVoiceMailNum
                + " voiceMailTag=" + mVoiceMailTag
                + " newVoiceMailNum=" + mNewVoiceMailNum
                + " newVoiceMailTag=" + mNewVoiceMailTag
                + " isVoiceMailFixed=" + mIsVoiceMailFixed
                + " mImsi=" + mImsi
                + " mncLength=" + mMncLength
                + " mailboxIndex=" + mMailboxIndex
                + " spn=" + mSpn;

    }

    /**
     * Generic ICC record loaded callback. Subclasses can call EF load methods on
     * {@link IccFileHandler} passing a Message for onLoaded with the what field set to
     * {@link #EVENT_GET_ICC_RECORD_DONE} and the obj field set to an instance
     * of this interface. The {@link #handleMessage} method in this class will print a
     * log message using {@link #getEfName()} and decrement {@link #mRecordsToLoad}.
     *
     * If the record load was successful, {@link #onRecordLoaded} will be called with the result.
     * Otherwise, an error log message will be output by {@link #handleMessage} and
     * {@link #onRecordLoaded} will not be called.
     */
    public interface IccRecordLoaded {
        String getEfName();
        void onRecordLoaded(AsyncResult ar);
    }

    // ***** Constructor
    public IccRecords(UiccCardApplication app, Context c, CommandsInterface ci) {
        mContext = c;
        mCi = ci;
        mFh = app.getIccFileHandler();
        mParentApp = app;

        mCi.registerForIccRefresh(this, EVENT_REFRESH, null);
    }

    /**
     * Call when the IccRecords object is no longer going to be used.
     */
    public void dispose() {
        mDestroyed.set(true);
        mCi.unregisterForIccRefresh(this);
        mParentApp = null;
        mFh = null;
        mCi = null;
        mContext = null;
    }

    public abstract void onReady();

    //***** Public Methods

    /*
     * Called to indicate that anyone could request records
     * in the future after this call, once records are loaded and registrants
     * have been notified. This indication could be used
     * to optimize when to actually fetch records from the card. We
     * don't need to fetch records from the card if it is of no use
     * to anyone
     *
     */
    void recordsRequired() {
        return;
    }

    public String getIccId() {
        return mIccId;
    }

    public void registerForRecordsLoaded(Handler h, int what, Object obj) {
        if (mDestroyed.get()) {
            return;
        }

        Registrant r = new Registrant(h, what, obj);
        mRecordsLoadedRegistrants.add(r);

        if (mRecordsToLoad == 0 && mRecordsRequested == true) {
            r.notifyRegistrant(new AsyncResult(null, null, null));
        }
    }
    public void unregisterForRecordsLoaded(Handler h) {
        mRecordsLoadedRegistrants.remove(h);
    }

    public void registerForImsiReady(Handler h, int what, Object obj) {
        if (mDestroyed.get()) {
            return;
        }

        Registrant r = new Registrant(h, what, obj);
        mImsiReadyRegistrants.add(r);

        if (mImsi != null) {
            r.notifyRegistrant(new AsyncResult(null, null, null));
        }
    }
    public void unregisterForImsiReady(Handler h) {
        mImsiReadyRegistrants.remove(h);
    }

    public void registerForRecordsEvents(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mRecordsEventsRegistrants.add(r);
    }
    public void unregisterForRecordsEvents(Handler h) {
        mRecordsEventsRegistrants.remove(h);
    }

    public void registerForNewSms(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mNewSmsRegistrants.add(r);
    }
    public void unregisterForNewSms(Handler h) {
        mNewSmsRegistrants.remove(h);
    }

    public void registerForNetworkSelectionModeAutomatic(
            Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mNetworkSelectionModeAutomaticRegistrants.add(r);
    }
    public void unregisterForNetworkSelectionModeAutomatic(Handler h) {
        mNetworkSelectionModeAutomaticRegistrants.remove(h);
    }

    /**
     * Get the International Mobile Subscriber ID (IMSI) on a SIM
     * for GSM, UMTS and like networks. Default is null if IMSI is
     * not supported or unavailable.
     *
     * @return null if SIM is not yet ready or unavailable
     */
    public String getIMSI() {
        return mImsi;
    }

    public String getMsisdnNumber() {
        return mMsisdn;
    }

    /**
     * Get the Group Identifier Level 1 (GID1) on a SIM for GSM.
     * @return null if SIM is not yet ready
     */
    public String getGid1() {
        return null;
    }

    /**
     * Set subscriber number to SIM record
     *
     * The subscriber number is stored in EF_MSISDN (TS 51.011)
     *
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param alphaTag alpha-tagging of the dailing nubmer (up to 10 characters)
     * @param number dailing nubmer (up to 20 digits)
     *        if the number starts with '+', then set to international TOA
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public void setMsisdnNumber(String alphaTag, String number,
            Message onComplete) {

        mMsisdn = number;
        mMsisdnTag = alphaTag;

        if (DBG) log("Set MSISDN: " + mMsisdnTag +" " + mMsisdn);


        AdnRecord adn = new AdnRecord(mMsisdnTag, mMsisdn);

        new AdnRecordLoader(mFh).updateEF(adn, EF_MSISDN, EF_EXT1, 1, null,
                obtainMessage(EVENT_SET_MSISDN_DONE, onComplete));
    }

    public String getMsisdnAlphaTag() {
        return mMsisdnTag;
    }

    public String getVoiceMailNumber() {
        return mVoiceMailNum;
    }

    /**
     * Return Service Provider Name stored in SIM (EF_SPN=0x6F46) or in RUIM (EF_RUIM_SPN=0x6F41)
     * @return null if SIM is not yet ready or no RUIM entry
     */
    public String getServiceProviderName() {
        return mSpn;
    }

    /**
     * Set voice mail number to SIM record
     *
     * The voice mail number can be stored either in EF_MBDN (TS 51.011) or
     * EF_MAILBOX_CPHS (CPHS 4.2)
     *
     * If EF_MBDN is available, store the voice mail number to EF_MBDN
     *
     * If EF_MAILBOX_CPHS is enabled, store the voice mail number to EF_CHPS
     *
     * So the voice mail number will be stored in both EFs if both are available
     *
     * Return error only if both EF_MBDN and EF_MAILBOX_CPHS fail.
     *
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param alphaTag alpha-tagging of the dailing nubmer (upto 10 characters)
     * @param voiceNumber dailing nubmer (upto 20 digits)
     *        if the number is start with '+', then set to international TOA
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public abstract void setVoiceMailNumber(String alphaTag, String voiceNumber,
            Message onComplete);

    public String getVoiceMailAlphaTag() {
        return mVoiceMailTag;
    }

    /**
     * Sets the SIM voice message waiting indicator records
     * @param line GSM Subscriber Profile Number, one-based. Only '1' is supported
     * @param countWaiting The number of messages waiting, if known. Use
     *                     -1 to indicate that an unknown number of
     *                      messages are waiting
     */
    public abstract void setVoiceMessageWaiting(int line, int countWaiting);

    /**
     * Called by GsmPhone to update VoiceMail count
     */
    public abstract int getVoiceMessageCount();

    /**
     * Called by STK Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    public abstract void onRefresh(boolean fileChanged, int[] fileList);

    /**
     * Called by subclasses (SimRecords and RuimRecords) whenever
     * IccRefreshResponse.REFRESH_RESULT_INIT event received
     */
    protected void onIccRefreshInit() {
        if (mParentApp.getState() == AppState.APPSTATE_READY) {
            // This will cause files to be reread
            sendMessage(obtainMessage(EVENT_APP_READY));
        }
    }

    public boolean getRecordsLoaded() {
        if (mRecordsToLoad == 0 && mRecordsRequested == true) {
            return true;
        } else {
            return false;
        }
    }

    //***** Overridden from Handler
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        switch (msg.what) {
            case EVENT_GET_ICC_RECORD_DONE:
                try {
                    ar = (AsyncResult) msg.obj;
                    IccRecordLoaded recordLoaded = (IccRecordLoaded) ar.userObj;
                    if (DBG) log(recordLoaded.getEfName() + " LOADED");

                    if (ar.exception != null) {
                        loge("Record Load Exception: " + ar.exception);
                    } else {
                        recordLoaded.onRecordLoaded(ar);
                    }
                }catch (RuntimeException exc) {
                    // I don't want these exceptions to be fatal
                    loge("Exception parsing SIM record: " + exc);
                } finally {
                    // Count up record load responses even if they are fails
                    onRecordLoaded();
                }
                break;
            case EVENT_REFRESH:
                ar = (AsyncResult)msg.obj;
                if (DBG) log("Card REFRESH occurred: ");
                if (ar.exception == null) {
                    handleRefresh((IccRefreshResponse)ar.result);
                } else {
                    loge("Icc refresh Exception: " + ar.exception);
                }
                break;
            case EVENT_GET_SMS_RECORD_SIZE_DONE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception != null) {
                    loge("Exception in EVENT_GET_SMS_RECORD_SIZE_DONE " + ar.exception);
                    break;
                }

                int[] recordSize = (int[])ar.result;
                try {
                    // recordSize[0]  is the record length
                    // recordSize[1]  is the total length of the EF file
                    // recordSize[2]  is the number of records in the EF file
                    mSmsCountOnIcc = recordSize[2];
                    log("EVENT_GET_SMS_RECORD_SIZE_DONE Size " + recordSize[0]
                            + " total " + recordSize[1]
                                    + " record " + recordSize[2]);
                } catch (ArrayIndexOutOfBoundsException exc) {
                    loge("ArrayIndexOutOfBoundsException in EVENT_GET_SMS_RECORD_SIZE_DONE: "
                            + exc.toString());
                }
                break;

            default:
                super.handleMessage(msg);
        }
    }

    protected abstract void handleFileUpdate(int efid);

    private void handleRefresh(IccRefreshResponse refreshResponse){
        if (refreshResponse == null) {
            if (DBG) log("handleRefresh received without input");
            return;
        }

        if (refreshResponse.aid != null &&
                !refreshResponse.aid.equals(mParentApp.getAid())) {
            // This is for different app. Ignore.
            return;
        }

        switch (refreshResponse.refreshResult) {
            case IccRefreshResponse.REFRESH_RESULT_FILE_UPDATE:
                if (DBG) log("handleRefresh with SIM_FILE_UPDATED");
                handleFileUpdate(refreshResponse.efId);
                break;
            case IccRefreshResponse.REFRESH_RESULT_INIT:
                if (DBG) log("handleRefresh with SIM_REFRESH_INIT");
                // need to reload all files (that we care about)
                //We will re-fetch the records when the app
                // goes back to the ready state. Nothing to do here.
                break;
            case IccRefreshResponse.REFRESH_RESULT_RESET:
                if (DBG) log("handleRefresh with SIM_REFRESH_RESET");
                //We will re-fetch the records when the app
                // goes back to the ready state. Nothing to do here.
                break;
            default:
                // unknown refresh operation
                if (DBG) log("handleRefresh with unknown operation");
                break;
        }
    }


    protected abstract void onRecordLoaded();

    protected abstract void onAllRecordsLoaded();

    /**
     * Returns the SpnDisplayRule based on settings on the SIM and the
     * specified plmn (currently-registered PLMN).  See TS 22.101 Annex A
     * and TS 51.011 10.3.11 for details.
     *
     * If the SPN is not found on the SIM, the rule is always PLMN_ONLY.
     * Generally used for GSM/UMTS and the like SIMs.
     */
    public abstract int getDisplayRule(String plmn);

    /**
     * Return true if "Restriction of menu options for manual PLMN selection"
     * bit is set or EF_CSP data is unavailable, return false otherwise.
     * Generally used for GSM/UMTS and the like SIMs.
     */
    public boolean isCspPlmnEnabled() {
        return false;
    }

    /**
     * Returns the 5 or 6 digit MCC/MNC of the operator that
     * provided the SIM card. Returns null of SIM is not yet ready
     * or is not valid for the type of IccCard. Generally used for
     * GSM/UMTS and the like SIMS
     */
    public String getOperatorNumeric() {
        return null;
    }

    /**
     * Check if call forward info is stored on SIM
     * @return true if call forward info is stored on SIM.
     */
    public boolean isCallForwardStatusStored() {
        return false;
    }

    /**
     * Get the current Voice call forwarding flag for GSM/UMTS and the like SIMs
     *
     * @return true if enabled
     */
    public boolean getVoiceCallForwardingFlag() {
        return false;
    }

    /**
     * Set the voice call forwarding flag for GSM/UMTS and the like SIMs
     *
     * @param line to enable/disable
     * @param enable
     * @param number to which CFU is enabled
     */
    public void setVoiceCallForwardingFlag(int line, boolean enable, String number) {
    }

    /**
     * Indicates wether SIM is in provisioned state or not.
     * Overridden only if SIM can be dynamically provisioned via OTA.
     *
     * @return true if provisioned
     */
    public boolean isProvisioned () {
        return true;
    }

    /**
     * Write string to log file
     *
     * @param s is the string to write
     */
    protected abstract void log(String s);

    /**
     * Write error string to log file.
     *
     * @param s is the string to write
     */
    protected abstract void loge(String s);

    /**
     * Return an interface to retrieve the ISIM records for IMS, if available.
     * @return the interface to retrieve the ISIM records, or null if not supported
     */
    public IsimRecords getIsimRecords() {
        return null;
    }

    /**
     * To get SMS capacity count on ICC card.
     */
    public int getSmsCapacityOnIcc() {
        if (DBG) log("getSmsCapacityOnIcc: " + mSmsCountOnIcc);
        return mSmsCountOnIcc;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("IccRecords: " + this);
        pw.println(" mDestroyed=" + mDestroyed);
        pw.println(" mCi=" + mCi);
        pw.println(" mFh=" + mFh);
        pw.println(" mParentApp=" + mParentApp);
        pw.println(" recordsLoadedRegistrants: size=" + mRecordsLoadedRegistrants.size());
        for (int i = 0; i < mRecordsLoadedRegistrants.size(); i++) {
            pw.println("  recordsLoadedRegistrants[" + i + "]="
                    + ((Registrant)mRecordsLoadedRegistrants.get(i)).getHandler());
        }
        pw.println(" mImsiReadyRegistrants: size=" + mImsiReadyRegistrants.size());
        for (int i = 0; i < mImsiReadyRegistrants.size(); i++) {
            pw.println("  mImsiReadyRegistrants[" + i + "]="
                    + ((Registrant)mImsiReadyRegistrants.get(i)).getHandler());
        }
        pw.println(" mRecordsEventsRegistrants: size=" + mRecordsEventsRegistrants.size());
        for (int i = 0; i < mRecordsEventsRegistrants.size(); i++) {
            pw.println("  mRecordsEventsRegistrants[" + i + "]="
                    + ((Registrant)mRecordsEventsRegistrants.get(i)).getHandler());
        }
        pw.println(" mNewSmsRegistrants: size=" + mNewSmsRegistrants.size());
        for (int i = 0; i < mNewSmsRegistrants.size(); i++) {
            pw.println("  mNewSmsRegistrants[" + i + "]="
                    + ((Registrant)mNewSmsRegistrants.get(i)).getHandler());
        }
        pw.println(" mNetworkSelectionModeAutomaticRegistrants: size="
                + mNetworkSelectionModeAutomaticRegistrants.size());
        for (int i = 0; i < mNetworkSelectionModeAutomaticRegistrants.size(); i++) {
            pw.println("  mNetworkSelectionModeAutomaticRegistrants[" + i + "]="
                    + ((Registrant)mNetworkSelectionModeAutomaticRegistrants.get(i)).getHandler());
        }
        pw.println(" mRecordsRequested=" + mRecordsRequested);
        pw.println(" mRecordsToLoad=" + mRecordsToLoad);
        pw.println(" iccid=" + mIccId);
        pw.println(" mMsisdn=" + mMsisdn);
        pw.println(" mMsisdnTag=" + mMsisdnTag);
        pw.println(" mVoiceMailNum=" + mVoiceMailNum);
        pw.println(" mVoiceMailTag=" + mVoiceMailTag);
        pw.println(" mNewVoiceMailNum=" + mNewVoiceMailNum);
        pw.println(" mNewVoiceMailTag=" + mNewVoiceMailTag);
        pw.println(" mIsVoiceMailFixed=" + mIsVoiceMailFixed);
        pw.println(" mImsi=" + mImsi);
        pw.println(" mMncLength=" + mMncLength);
        pw.println(" mMailboxIndex=" + mMailboxIndex);
        pw.println(" mSpn=" + mSpn);
        pw.flush();
    }

}
