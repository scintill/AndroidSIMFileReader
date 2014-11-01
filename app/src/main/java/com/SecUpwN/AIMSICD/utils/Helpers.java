/**   Copyright (C) 2013  Louis Teboul (a.k.a Androguide)
 *
 *    admin@pimpmyrom.org  || louisteboul@gmail.com
 *    http://pimpmyrom.org || http://androguide.fr
 *    71 quai Clémenceau, 69300 Caluire-et-Cuire, FRANCE.
 *
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *      You should have received a copy of the GNU General Public License along
 *      with this program; if not, write to the Free Software Foundation, Inc.,
 *      51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 **/

package com.SecUpwN.AIMSICD.utils;

import android.util.Log;

import java.io.File;

public class Helpers {

    private static final String TAG = "AIMSICD_Helpers";

    /**
     * Checks device for SuperUser permission
     *
     * @return If SU was granted or denied
     */
    @SuppressWarnings("MethodWithMultipleReturnPoints")
    public static boolean checkSu() {
        if (!new File("/system/bin/su").exists()
                && !new File("/system/xbin/su").exists()) {
            Log.e(TAG, "su binary does not exist!!!");
            return false; // tell caller to bail...
        }
        try {
            if (CMDProcessor.runSuCommand("ls /data/app-private").success()) {
                Log.i(TAG, " SU exists and we have permission");
                return true;
            } else {
                Log.i(TAG, " SU exists but we don't have permission");
                return false;
            }
        } catch (NullPointerException e) {
            Log.e(TAG, "NullPointer throw while looking for su binary", e);
            return false;
        }
    }

}

