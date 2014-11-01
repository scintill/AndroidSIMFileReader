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

public final class CMDProcessor {

    private static final String TAG = "CMDProcessor";

    public CMDProcessor() {

    }

    /* Run a system command with full redirection */
    public static ChildProcess startSysCmd(String[] cmdarray, String childStdin) {
        return new ChildProcess(cmdarray, childStdin);
    }

    public static CommandResult runSysCmd(String[] cmdarray, String childStdin) {
        ChildProcess proc = startSysCmd(cmdarray, childStdin);
        proc.waitFinished();
        return proc.getResult();
    }

    public static ChildProcess startShellCommand(String cmd) {
        String[] cmdarray = new String[3];
        cmdarray[0] = "sh";
        cmdarray[1] = "-c";
        cmdarray[2] = cmd;
        return startSysCmd(cmdarray, null);
    }

    public static CommandResult runShellCommand(String cmd) {
        ChildProcess proc = startShellCommand(cmd);
        proc.waitFinished();
        return proc.getResult();
    }

    public static ChildProcess startSuCommand(String cmd) {
        String[] cmdarray = new String[3];
        cmdarray[0] = "su";
        cmdarray[1] = "-c";
        cmdarray[2] = cmd;
        return startSysCmd(cmdarray, null);
    }

    private static boolean mSuImplChecked = false;
    private static boolean mSuImplIsSuperSU = false;

    public static ChildProcess startSuCommand(String uid, String cmd) {
        if (!mSuImplChecked) {
            // figure out which su binary we've got, as I haven't (yet?) figured out a syntax
            // that is compatible with both SuperSU and CWM Superuser

            // assume canSu() has already been used, and thus the command exists, but verify
            CommandResult versionResult = runShellCommand("su -v");
            if (!versionResult.success()) {
                throw new RuntimeException("unable to check su version -- is it not installed?");
            }
            mSuImplChecked = true;
            mSuImplIsSuperSU = versionResult.getStdout().contains("SUPERSU");
        }

        String[] cmdarray;
        if (mSuImplIsSuperSU) {
            cmdarray = new String[4];
            cmdarray[0] = "su";
            cmdarray[1] = uid;
            cmdarray[2] = "-c";
            cmdarray[3] = cmd;
        } else {
            cmdarray = new String[5];
            cmdarray[0] = "su";
            cmdarray[1] = "-c";
            cmdarray[2] = cmd;
            cmdarray[3] = "--";
            cmdarray[4] = uid;
        }
        return startSysCmd(cmdarray, null);
    }

    public static CommandResult runSuCommand(String cmd) {
        ChildProcess proc = startSuCommand(cmd);
        proc.waitFinished();
        return proc.getResult();
    }

    public static CommandResult runSuCommand(String uid, String cmd) {
        Log.d(TAG, "runSuCommand "+uid+" "+cmd);
        ChildProcess proc = startSuCommand(uid, cmd);
        proc.waitFinished();
        return proc.getResult();
    }

    public static boolean canSU() {
        CommandResult r = runShellCommand("id");
        StringBuilder out = new StringBuilder(0);
        out.append(r.getStdout());
        out.append(" ; ");
        out.append(r.getStderr());
        Log.d(TAG, "canSU() su[" + r.getExitValue() + "]: " + out);
        return r.success();
    }
}