/*
 * Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
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

package net.scintill.simio.telephony;

import android.os.Message;

import net.scintill.simio.telephony.uicc.IccRecords;

public interface CommandsInterface {

    void iccIOForApp(int command, int fileid, String path, int p1, int p2, int p3, String data, String pin2, String aid, Message result);

    void registerForIccRefresh(IccRecords iccRecords, int eventRefresh, Object o);

    void unregisterForIccRefresh(IccRecords iccRecords);

    void getCDMASubscription(Message message);

    void getIMSIForApp(String aid, Message message);

    void setRadioPower(boolean b, Object o);

    void dispose();

}
