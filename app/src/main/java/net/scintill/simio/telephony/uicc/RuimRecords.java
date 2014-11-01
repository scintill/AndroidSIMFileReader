/*
 * Copyright (C) 2008 The Android Open Source Project
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


import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.util.Log;

import net.scintill.simio.telephony.CommandsInterface;
import net.scintill.simio.telephony.GsmAlphabet;
import net.scintill.simio.telephony.MccTable;

import net.scintill.simio.telephony.cdma.sms.UserData;
import net.scintill.simio.telephony.uicc.IccCardApplicationStatus.AppType;
import net.scintill.simio.telephony.uicc.IccCardApplicationStatus.AppState;


/**
 * {@hide}
 */
public final class RuimRecords extends IccRecords {
    static final String LOG_TAG = "RuimRecords";

    private boolean  mOtaCommited=false;

    // ***** Instance Variables

    private String mMyMobileNumber;
    private String mMin2Min1;

    private String mPrlVersion;
    private boolean mRecordsRequired = false;
    // From CSIM application
    private byte[] mEFpl = null;
    private byte[] mEFli = null;
    boolean mCsimSpnDisplayCondition = false;
    private String mMdn;
    private String mMin;
    private String mHomeSystemId;
    private String mHomeNetworkId;
    private boolean mMSIMRecordeEnabled = false;

    @Override
    public String toString() {
        return "RuimRecords: " + super.toString()
                + " m_ota_commited" + mOtaCommited
                + " mMyMobileNumber=" + "xxxx"
                + " mMin2Min1=" + mMin2Min1
                + " mPrlVersion=" + mPrlVersion
                + " mEFpl=" + mEFpl
                + " mEFli=" + mEFli
                + " mCsimSpnDisplayCondition=" + mCsimSpnDisplayCondition
                + " mMdn=" + mMdn
                + " mMin=" + mMin
                + " mHomeSystemId=" + mHomeSystemId
                + " mHomeNetworkId=" + mHomeNetworkId;
    }

    //Constants
    //MNC length in case of CSIM/RUIM IMSI is 2 as per spec C.S0065 section 5.2.2
    private static final int CSIM_IMSI_MNC_LENGTH = 2;

    // ***** Event Constants
    private static final int EVENT_GET_IMSI_DONE = 3;
    private static final int EVENT_GET_DEVICE_IDENTITY_DONE = 4;
    private static final int EVENT_GET_ICCID_DONE = 5;
    private static final int EVENT_GET_CDMA_SUBSCRIPTION_DONE = 10;
    private static final int EVENT_UPDATE_DONE = 14;
    private static final int EVENT_GET_SST_DONE = 17;
    private static final int EVENT_GET_ALL_SMS_DONE = 18;
    private static final int EVENT_MARK_SMS_READ_DONE = 19;

    private static final int EVENT_SMS_ON_RUIM = 21;
    private static final int EVENT_GET_SMS_DONE = 22;

    public RuimRecords(UiccCardApplication app, Context c, CommandsInterface ci) {
        super(app, c, ci);

        mRecordsRequested = false;  // No load request is made till SIM ready

        // recordsToLoad is set to 0 because no requests are made yet
        mRecordsToLoad = 0;

        // NOTE the EVENT_SMS_ON_RUIM is not registered

        // Start off by setting empty state
        resetRecords();

        mParentApp.registerForReady(this, EVENT_APP_READY, null);
        if (DBG) log("RuimRecords X ctor this=" + this);
    }

    @Override
    public void dispose() {
        if (DBG) log("Disposing RuimRecords " + this);
        //Unregister for all events
        mParentApp.unregisterForReady(this);
        resetRecords();
        super.dispose();
    }

    @Override
    protected void finalize() {
        if(DBG) log("RuimRecords finalized");
    }

    protected void resetRecords() {
        mMncLength = UNINITIALIZED;
        mIccId = null;

        // Don't clean up PROPERTY_ICC_OPERATOR_ISO_COUNTRY and
        // PROPERTY_ICC_OPERATOR_NUMERIC here. Since not all CDMA
        // devices have RUIM, these properties should keep the original
        // values, e.g. build time settings, when there is no RUIM but
        // set new values when RUIM is available and loaded.

        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        mRecordsRequested = false;
    }

    public String getMdnNumber() {
        return mMyMobileNumber;
    }

    public String getCdmaMin() {
         return mMin2Min1;
    }

    /** Returns null if RUIM is not yet ready */
    public String getPrlVersion() {
        return mPrlVersion;
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceNumber, Message onComplete){
        // In CDMA this is Operator/OEM dependent
        AsyncResult.forMessage((onComplete)).exception =
                new IccException("setVoiceMailNumber not implemented");
        onComplete.sendToTarget();
        loge("method setVoiceMailNumber is not implemented");
    }

    /**
     * Called by CCAT Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    @Override
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all RUIM records that we cache.
            fetchRuimRecords();
        }
    }

    private int decodeImsiDigits(int digits, int length) {
        // Per C.S0005 section 2.3.1.
        int constant = 0;
        for (int i = 0; i < length; i++ ) {
            constant = (constant * 10) + 1;
        }

        digits += constant;

        for (int i = 0, denominator = 1; i < length; i++) {
            digits = ((digits / denominator) % 10 == 0) ? (digits - (10 * denominator)) : digits;
            denominator *= 10;
        }
        return digits;
    }

    /**
     * Decode utility to decode IMSI from data read from EF_IMSIM
     * Please refer to
     *       // C.S0065 section 5.2.2 for IMSI_M encoding
     *       // C.S0005 section 2.3.1 for MIN encoding in IMSI_M.
     */
    private String decodeImsi(byte[] data) {
        // Retrieve the MCC and digits 11 and 12
        int mcc_data = ((0x03 & data[9]) << 8) | (0xFF & data[8]);
        int mcc = decodeImsiDigits(mcc_data, 3);
        int digits_11_12_data = data[6] & 0x7f;
        int digits_11_12 = decodeImsiDigits(digits_11_12_data, 2);

        // Retrieve 10 MIN digits
        int first3digits = ((0x03 & data[2]) << 8) + (0xFF & data[1]);
        int second3digits = (((0xFF & data[5]) << 8) | (0xFF & data[4])) >> 6;
        int digit7 = 0x0F & (data[4] >> 2);
        if (digit7 > 0x09) digit7 = 0;
        int last3digits = ((0x03 & data[4]) << 8) | (0xFF & data[3]);

        first3digits = decodeImsiDigits(first3digits, 3);
        second3digits = decodeImsiDigits(second3digits, 3);
        last3digits = decodeImsiDigits(last3digits, 3);

        StringBuilder builder = new StringBuilder();
        builder.append(String.format(Locale.US, "%03d", mcc));
        builder.append(String.format(Locale.US, "%02d", digits_11_12));
        builder.append(String.format(Locale.US, "%03d", first3digits));
        builder.append(String.format(Locale.US, "%03d", second3digits));
        builder.append(String.format(Locale.US, "%d", digit7));
        builder.append(String.format(Locale.US, "%03d", last3digits));
        return  builder.toString();
    }


    /**
     * Returns the 5 or 6 digit MCC/MNC of the operator that
     *  provided the RUIM card. Returns null of RUIM is not yet ready
     */

    @Override
    public String getOperatorNumeric() {
        if (mImsi == null) {
            return null;
        }

        if (mMncLength != UNINITIALIZED && mMncLength != UNKNOWN) {
            // Length = length of MCC + length of MNC
            // length of mcc = 3 (3GPP2 C.S0005 - Section 2.3)
            return mImsi.substring(0, 3 + mMncLength);
        }

        // Guess the MNC length based on the MCC if we don't
        // have a valid value in ef[ad]

        int mcc = Integer.parseInt(mImsi.substring(0,3));
        return mImsi.substring(0, 3 + CSIM_IMSI_MNC_LENGTH);
    }

    // Refer to ETSI TS 102.221
    private class EfPlLoaded implements IccRecordLoaded {
        @Override
        public String getEfName() {
            return "EF_PL";
        }

        @Override
        public void onRecordLoaded(AsyncResult ar) {
            mEFpl = (byte[]) ar.result;
            if (DBG) log("EF_PL=" + IccUtils.bytesToHexString(mEFpl));
        }
    }

    // Refer to C.S0065 5.2.26
    private class EfCsimLiLoaded implements IccRecordLoaded {
        @Override
        public String getEfName() {
            return "EF_CSIM_LI";
        }

        @Override
        public void onRecordLoaded(AsyncResult ar) {
            mEFli = (byte[]) ar.result;
            // convert csim efli data to iso 639 format
            for (int i = 0; i < mEFli.length; i+=2) {
                switch(mEFli[i+1]) {
                case 0x01: mEFli[i] = 'e'; mEFli[i+1] = 'n';break;
                case 0x02: mEFli[i] = 'f'; mEFli[i+1] = 'r';break;
                case 0x03: mEFli[i] = 'e'; mEFli[i+1] = 's';break;
                case 0x04: mEFli[i] = 'j'; mEFli[i+1] = 'a';break;
                case 0x05: mEFli[i] = 'k'; mEFli[i+1] = 'o';break;
                case 0x06: mEFli[i] = 'z'; mEFli[i+1] = 'h';break;
                case 0x07: mEFli[i] = 'h'; mEFli[i+1] = 'e';break;
                default: mEFli[i] = ' '; mEFli[i+1] = ' ';
                }
            }

            if (DBG) log("EF_LI=" + IccUtils.bytesToHexString(mEFli));
        }
    }

    // Refer to C.S0065 5.2.32
    private class EfCsimSpnLoaded implements IccRecordLoaded {
        @Override
        public String getEfName() {
            return "EF_CSIM_SPN";
        }

        @Override
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            if (DBG) log("CSIM_SPN=" +
                         IccUtils.bytesToHexString(data));

            // C.S0065 for EF_SPN decoding
            mCsimSpnDisplayCondition = ((0x01 & data[0]) != 0);

            int encoding = data[1];
            int language = data[2];
            byte[] spnData = new byte[32];
            int len = ((data.length - 3) < 32) ? (data.length - 3) : 32;
            System.arraycopy(data, 3, spnData, 0, len);

            int numBytes;
            for (numBytes = 0; numBytes < spnData.length; numBytes++) {
                if ((spnData[numBytes] & 0xFF) == 0xFF) break;
            }

            if (numBytes == 0) {
                mSpn = "";
                return;
            }
            try {
                switch (encoding) {
                case UserData.ENCODING_OCTET:
                case UserData.ENCODING_LATIN:
                    mSpn = new String(spnData, 0, numBytes, "ISO-8859-1");
                    break;
                case UserData.ENCODING_IA5:
                case UserData.ENCODING_GSM_7BIT_ALPHABET:
                    mSpn = GsmAlphabet.gsm7BitPackedToString(spnData, 0, (numBytes*8)/7);
                    break;
                case UserData.ENCODING_7BIT_ASCII:
                    mSpn =  new String(spnData, 0, numBytes, "US-ASCII");
                        // To address issues with incorrect encoding scheme
                        // programmed in some commercial CSIM cards, the decoded
                        // SPN is checked to have characters in printable ASCII
                        // range. If not, they are decoded with
                        // ENCODING_GSM_7BIT_ALPHABET scheme.
                    if (!isPrintableAsciiOnly(mSpn)) {
                        if (DBG) log("Some corruption in SPN decoding = " + mSpn);
                        if (DBG) log("Using ENCODING_GSM_7BIT_ALPHABET scheme...");
                        mSpn = GsmAlphabet.gsm7BitPackedToString(spnData, 0, (numBytes*8)/7);
                    }
                    break;
                case UserData.ENCODING_UNICODE_16:
                    mSpn =  new String(spnData, 0, numBytes, "utf-16");
                    break;
                default:
                    log("SPN encoding not supported");
                }
            } catch(Exception e) {
                log("spn decode error: " + e);
            }
            if (DBG) log("spn=" + mSpn);
            if (DBG) log("spnCondition=" + mCsimSpnDisplayCondition);
        }
    }

    // hidden API from TextUtils
    private static boolean isPrintableAsciiOnly(final CharSequence str) {
        final int len = str.length();
        for (int i = 0; i < len; i++) {
            if (!isPrintableAscii(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrintableAscii(final char c) {
        final int asciiFirst = 0x20;
        final int asciiLast = 0x7E;  // included
        return (asciiFirst <= c && c <= asciiLast) || c == '\r' || c == '\n';
    }

    private class EfCsimMdnLoaded implements IccRecordLoaded {
        @Override
        public String getEfName() {
            return "EF_CSIM_MDN";
        }

        @Override
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            if (DBG) log("CSIM_MDN=" + IccUtils.bytesToHexString(data));
            // Refer to C.S0065 5.2.35
            int mdnDigitsNum = 0x0F & data[0];
            mMdn = IccUtils.cdmaBcdToString(data, 1, mdnDigitsNum);
            if (DBG) log("CSIM MDN=" + mMdn);
        }
    }

    private class EfCsimImsimLoaded implements IccRecordLoaded {
        @Override
        public String getEfName() {
            return "EF_CSIM_IMSIM";
        }

        @Override
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;

            if (data == null || data.length < 10) {
                log("Invalid IMSI from EF_CSIM_IMSIM " + IccUtils.bytesToHexString(data));
                mImsi = null;
                mMin = null;
                return;
            }
            if (DBG) log("CSIM_IMSIM=" + IccUtils.bytesToHexString(data));

            // C.S0065 section 5.2.2 for IMSI_M encoding
            // C.S0005 section 2.3.1 for MIN encoding in IMSI_M.
            boolean provisioned = ((data[7] & 0x80) == 0x80);

            if (provisioned) {
                mImsi = decodeImsi(data);
                if (null != mImsi) {
                    mMin = mImsi.substring(5, 15);
                }
                log("IMSI: " + mImsi.substring(0, 5) + "xxxxxxxxx");

            } else {
                if (DBG) log("IMSI not provisioned in card");
            }

            //Update MccTable with the retrieved IMSI
            String operatorNumeric = getOperatorNumeric();
            if (operatorNumeric != null) {
                if(operatorNumeric.length() <= 6) {
                    mMSIMRecordeEnabled = true;
                    MccTable.updateMccMncConfiguration(mContext, operatorNumeric, false);
                }
            }

            mImsiReadyRegistrants.notifyRegistrants();
        }
   }

    private class EfCsimCdmaHomeLoaded implements IccRecordLoaded {
        @Override
        public String getEfName() {
            return "EF_CSIM_CDMAHOME";
        }

        @Override
        public void onRecordLoaded(AsyncResult ar) {
            // Per C.S0065 section 5.2.8
            ArrayList<byte[]> dataList = (ArrayList<byte[]>) ar.result;
            if (DBG) log("CSIM_CDMAHOME data size=" + dataList.size());
            if (dataList.isEmpty()) {
                return;
            }
            StringBuilder sidBuf = new StringBuilder();
            StringBuilder nidBuf = new StringBuilder();

            for (byte[] data : dataList) {
                if (data.length == 5) {
                    int sid = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
                    int nid = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
                    sidBuf.append(sid).append(',');
                    nidBuf.append(nid).append(',');
                }
            }
            // remove trailing ","
            sidBuf.setLength(sidBuf.length()-1);
            nidBuf.setLength(nidBuf.length()-1);

            mHomeSystemId = sidBuf.toString();
            mHomeNetworkId = nidBuf.toString();
        }
    }

    private class EfCsimEprlLoaded implements IccRecordLoaded {
        @Override
        public String getEfName() {
            return "EF_CSIM_EPRL";
        }
        @Override
        public void onRecordLoaded(AsyncResult ar) {
            onGetCSimEprlDone(ar);
        }
    }

    private void onGetCSimEprlDone(AsyncResult ar) {
        // C.S0065 section 5.2.57 for EFeprl encoding
        // C.S0016 section 3.5.5 for PRL format.
        byte[] data = (byte[]) ar.result;
        if (DBG) log("CSIM_EPRL=" + IccUtils.bytesToHexString(data));

        // Only need the first 4 bytes of record
        if (data.length > 3) {
            int prlId = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            mPrlVersion = Integer.toString(prlId);
        }
        if (DBG) log("CSIM PRL version=" + mPrlVersion);
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        byte data[];

        boolean isRecordLoadResponse = false;

        if (mDestroyed.get()) {
            loge("Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }

        try { switch (msg.what) {
            case EVENT_APP_READY:
                onReady();
                break;

            case EVENT_GET_DEVICE_IDENTITY_DONE:
                log("Event EVENT_GET_DEVICE_IDENTITY_DONE Received");
            break;

            /* IO events */
            case EVENT_GET_IMSI_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    loge("Exception querying IMSI, Exception:" + ar.exception);
                    break;
                }

                mImsi = (String) ar.result;

                // IMSI (MCC+MNC+MSIN) is at least 6 digits, but not more
                // than 15 (and usually 15).
                if (mImsi != null && (mImsi.length() < 6 || mImsi.length() > 15)) {
                    loge("invalid IMSI " + mImsi);
                    mImsi = null;
                }

                log("IMSI: " + mImsi.substring(0, 6) + "xxxxxxxxx");

                String operatorNumeric = getOperatorNumeric();
                if (operatorNumeric != null) {
                    if(operatorNumeric.length() <= 6) {
                        MccTable.updateMccMncConfiguration(mContext, operatorNumeric, false);
                    }
                }
                break;

            case EVENT_GET_CDMA_SUBSCRIPTION_DONE:
                ar = (AsyncResult)msg.obj;
                String localTemp[] = (String[])ar.result;
                if (ar.exception != null) {
                    break;
                }

                mMyMobileNumber = localTemp[0];
                mMin2Min1 = localTemp[3];
                mPrlVersion = localTemp[4];

                log("MDN: " + mMyMobileNumber + " MIN: " + mMin2Min1);

            break;

            case EVENT_GET_ICCID_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                mIccId = IccUtils.bcdToString(data, 0, data.length);

                log("iccid: " + mIccId);

            break;

            case EVENT_UPDATE_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    Log.i(LOG_TAG, "RuimRecords update failed", ar.exception);
                }
            break;

            case EVENT_GET_ALL_SMS_DONE:
            case EVENT_MARK_SMS_READ_DONE:
            case EVENT_SMS_ON_RUIM:
            case EVENT_GET_SMS_DONE:
                Log.w(LOG_TAG, "Event not supported: " + msg.what);
                break;

            // TODO: probably EF_CST should be read instead
            case EVENT_GET_SST_DONE:
                log("Event EVENT_GET_SST_DONE Received");
            break;

            default:
                super.handleMessage(msg);   // IccRecords handles generic record load responses

        }}catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            Log.w(LOG_TAG, "Exception parsing RUIM record", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    private String findBestLanguage(byte[] languages) {
        String bestMatch = null;
        String[] locales = mContext.getAssets().getLocales();

        if ((languages == null) || (locales == null)) return null;

        // Each 2-bytes consists of one language
        for (int i = 0; (i + 1) < languages.length; i += 2) {
            try {
                String lang = new String(languages, i, 2, "ISO-8859-1");
                for (int j = 0; j < locales.length; j++) {
                    if (locales[j] != null && locales[j].length() >= 2 &&
                        locales[j].substring(0, 2).equals(lang)) {
                        return lang;
                    }
                }
                if (bestMatch != null) break;
            } catch(java.io.UnsupportedEncodingException e) {
                log ("Failed to parse SIM language records");
            }
        }
        // no match found. return null
        return null;
    }

    private void setLocaleFromCsim() {
        String prefLang = null;
        // check EFli then EFpl
        prefLang = findBestLanguage(mEFli);

        if (prefLang == null) {
            prefLang = findBestLanguage(mEFpl);
        }

        if (prefLang != null) {
            // check country code from SIM
            String imsi = getIMSI();
            String country = null;
            if (imsi != null) {
                country = MccTable.countryCodeForMcc(
                                    Integer.parseInt(imsi.substring(0,3)));
            }
            log("Setting locale to " + prefLang + "_" + country);
            MccTable.setSystemLocale(mContext, prefLang, country);
        } else {
            log ("No suitable CSIM selected locale");
        }
    }

    @Override
    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        mRecordsToLoad -= 1;
        if (DBG) log("onRecordLoaded " + mRecordsToLoad + " requested: " + mRecordsRequested);

        if (mRecordsToLoad == 0 && mRecordsRequested == true) {
            onAllRecordsLoaded();
        } else if (mRecordsToLoad < 0) {
            loge("recordsToLoad <0, programmer error suspected");
            mRecordsToLoad = 0;
        }
    }

    @Override
    protected void onAllRecordsLoaded() {
        if (DBG) log("record load complete");

        setLocaleFromCsim();
        mRecordsLoadedRegistrants.notifyRegistrants(
            new AsyncResult(null, null, null));
    }

    @Override
    public void onReady() {
        fetchRuimRecords();

        mCi.getCDMASubscription(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_DONE));
    }

    /**
     * Called by IccCardProxy before it requests records.
     * We use this as a trigger to read records from the card.
     */
    void recordsRequired() {
        if (DBG) log("recordsRequired mRecordsRequired = " + mRecordsRequired);
        if (!mRecordsRequired) {
            mRecordsRequired = true;
            // trigger to retrieve all records
            fetchRuimRecords();
        }
    }

    private void fetchRuimRecords() {
        /* Don't read records if we don't expect
         * anyone to ask for them
         *
         * If records are not required by anyone OR
         * the app is not ready then bail
         */
        if (!mRecordsRequired || AppState.APPSTATE_READY != mParentApp.getState()) {
            if (DBG) log("fetchRuimRecords: Abort fetching records rRecordsRequested = "
                            + mRecordsRequested
                            + " state = " + mParentApp.getState()
                            + " required = " + mRecordsRequired);
            return;
        }

        mRecordsRequested = true;

        if (DBG) log("fetchRuimRecords " + mRecordsToLoad);
        if (!mMSIMRecordeEnabled) {
            mCi.getIMSIForApp(mParentApp.getAid(), obtainMessage(EVENT_GET_IMSI_DONE));
            mRecordsToLoad++;
        }

        mFh.loadEFTransparent(EF_ICCID,
                obtainMessage(EVENT_GET_ICCID_DONE));
        mRecordsToLoad++;

        mFh.loadEFTransparent(EF_PL,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfPlLoaded()));
        mRecordsToLoad++;

        mFh.loadEFTransparent(EF_CSIM_LI,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimLiLoaded()));
        mRecordsToLoad++;

        mFh.loadEFTransparent(EF_CSIM_SPN,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimSpnLoaded()));
        mRecordsToLoad++;

        mFh.loadEFLinearFixed(EF_CSIM_MDN, 1,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimMdnLoaded()));
        mRecordsToLoad++;

        mFh.loadEFTransparent(EF_CSIM_IMSIM,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimImsimLoaded()));
        mRecordsToLoad++;

        mFh.loadEFLinearFixedAll(EF_CSIM_CDMAHOME,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimCdmaHomeLoaded()));
        mRecordsToLoad++;

        // Entire PRL could be huge. We are only interested in
        // the first 4 bytes of the record.
        mFh.loadEFTransparent(EF_CSIM_EPRL, 4,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimEprlLoaded()));
        mRecordsToLoad++;

        mFh.getEFLinearRecordSize(EF_SMS, obtainMessage(EVENT_GET_SMS_RECORD_SIZE_DONE));

        if (DBG) log("fetchRuimRecords " + mRecordsToLoad + " requested: " + mRecordsRequested);
        // Further records that can be inserted are Operator/OEM dependent
    }

    /**
     * {@inheritDoc}
     *
     * No Display rule for RUIMs yet.
     */
    @Override
    public int getDisplayRule(String plmn) {
        // TODO together with spn
        return 0;
    }

    @Override
    public boolean isProvisioned() {
        // If UICC card has CSIM app, look for MDN and MIN field
        // to determine if the SIM is provisioned.  Otherwise,
        // consider the SIM is provisioned. (for case of ordinal
        // USIM only UICC.)
        // If PROPERTY_TEST_CSIM is defined, bypess provision check
        // and consider the SIM is provisioned.

        if (mParentApp == null) {
            return false;
        }

        if (mParentApp.getType() == AppType.APPTYPE_CSIM &&
            ((mMdn == null) || (mMin == null))) {
            return false;
        }
        return true;
    }

    @Override
    public void setVoiceMessageWaiting(int line, int countWaiting) {
        // Will be used in future to store voice mail count in UIM
        // C.S0023-D_v1.0 does not have a file id in UIM for MWI
        log("RuimRecords:setVoiceMessageWaiting - NOP for CDMA");
    }

    @Override
    public int getVoiceMessageCount() {
        // Will be used in future to retrieve voice mail count for UIM
        // C.S0023-D_v1.0 does not have a file id in UIM for MWI
        log("RuimRecords:getVoiceMessageCount - NOP for CDMA");
        return 0;
    }

    @Override
    protected void handleFileUpdate(int efid) {
        fetchRuimRecords();
    }

    public String getMdn() {
        return mMdn;
    }

    public String getMin() {
        return mMin;
    }

    public String getSid() {
        return mHomeSystemId;
    }

    public String getNid() {
        return mHomeNetworkId;
    }

    public boolean getCsimSpnDisplayCondition() {
        return mCsimSpnDisplayCondition;
    }
    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[RuimRecords] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[RuimRecords] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("RuimRecords: " + this);
        pw.println(" extends:");
        super.dump(fd, pw, args);
        pw.println(" mOtaCommited=" + mOtaCommited);
        pw.println(" mMyMobileNumber=" + mMyMobileNumber);
        pw.println(" mMin2Min1=" + mMin2Min1);
        pw.println(" mPrlVersion=" + mPrlVersion);
        pw.println(" mEFpl[]=" + Arrays.toString(mEFpl));
        pw.println(" mEFli[]=" + Arrays.toString(mEFli));
        pw.println(" mCsimSpnDisplayCondition=" + mCsimSpnDisplayCondition);
        pw.println(" mMdn=" + mMdn);
        pw.println(" mMin=" + mMin);
        pw.println(" mHomeSystemId=" + mHomeSystemId);
        pw.println(" mHomeNetworkId=" + mHomeNetworkId);
        pw.flush();
    }

}
