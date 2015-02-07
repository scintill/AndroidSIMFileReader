# Android SIM File Reader

This is an app to demonstrate reading SIM files in Android, with several methods.  They are: using the SEEK API, injecting a new service implementation into the phone process, and AT/Hayes commands.

The app uses a [factory method](https://github.com/scintill/AndroidSIMFileReader/blob/fd78417e10d5d0db74819b0057f740a444f701e9/app/src/main/java/net/scintill/simio/CommandsInterfaceFactory.java#L39) to transparently find a working interface to do SIM I/O.  The code to locate and parse the SIM files is mostly lifted from CyanogenMod (though AOSP code should be pretty much the same), with my SIM I/O classes taking the place of the RIL class that these classes were written to use.

## SEEK API

This refers to [Secure Element Evaluation Kit for the Android platform](https://code.google.com/p/seek-for-android/).  You may not need the patches from this project, because it seems to have been merged into several Android distributions.  This app does not use or need the official SEEK service APK, it directly invokes the backend methods in the telephony service.  This is necessary to circumvent permissions checking, and is also useful for CyanogenMod 11, which does not seem to have a working OpenMobile service, but does have the SEEK patches.    Implemented [here](https://github.com/scintill/AndroidSIMFileReader/blob/fd78417e10d5d0db74819b0057f740a444f701e9/app/src/main/java/net/scintill/simio/TelephonySeekServiceCommandsInterface.java).

## Injected service

The code in the `com.android.phone` process is hot-patched using [ddi](https://github.com/crmulliner/ddi) to allow new service calls for invoking the SIM I/O methods the RIL class has (which is how Android reads your phone number, voicemail number, etc. from the SIM.)  Should be fairly compatible, but it uses so much undocumented/private behavior that it can't really be guaranteed.  I recommend killing your `com.android.phone` process (it will restart), to flush out my code, when you're done using it.  Client side [here](https://github.com/scintill/AndroidSIMFileReader/blob/fd78417e10d5d0db74819b0057f740a444f701e9/app/src/main/java/net/scintill/simio/RilExtenderCommandsInterface.java), remote (in phone process) Java part [here](https://github.com/scintill/AndroidSIMFileReader/blob/fd78417e10d5d0db74819b0057f740a444f701e9/app/src/main/java/net/scintill/simio/RilExtender.java), native glue [here](https://github.com/scintill/AndroidSIMFileReader/blob/fd78417e10d5d0db74819b0057f740a444f701e9/app/src/native/rilinject/jni/rilinject.c).

## AT Commands

Compatibility is probably quite limited right now, but this works on my own Qualcomm device.  AT commands are sent to the `/dev/smd7` device, and responses are read and parsed.  It is a bit fragile; sometimes the superuser'd processes that read/write to the device don't terminate correctly, meaning the next time the app runs it won't be able to connect to the device correctly, and may hang or crash.  SIM I/O interface [here](https://github.com/scintill/AndroidSIMFileReader/blob/fd78417e10d5d0db74819b0057f740a444f701e9/app/src/main/java/net/scintill/simio/AtCommandInterface.java), general AT command interface [here](https://github.com/scintill/AndroidSIMFileReader/tree/fd78417e10d5d0db74819b0057f740a444f701e9/app/src/main/java/com/SecUpwN/AIMSICD/utils/atcmd).

## Other unimplemented methods

### TelephonyManager.iccExchangeSimIO()

Android 5.0 appears to have introduced a public [iccExchangeSimIO\(\)](https://developer.android.com/reference/android/telephony/TelephonyManager.html#iccExchangeSimIO\(int, int, int, int, int, java.lang.String\)) API.  I haven't tried it, but it looks like it can do what we need.  It requires privileges, but that shouldn't be a problem.

## Privacy

There are several places where potentially sensitive information (your phone number, your [TMSI](https://en.wikipedia.org/wiki/Mobility_management#TMSI), your [LAI](https://en.wikipedia.org/wiki/Location_area_identity)) is sent to `logcat`.  Beware, other apps or users could view this log and learn these data about your phone/SIM card.

## Building

Something is wrong with the build process.  To reliably build the RilExtender (injected service), you have to build twice.  The first build doesn't properly package the secondary dex file in to the app.

## Logs

Example logcat command to filter to output from this app (also shown on the app's screen):

    adb logcat -s SIMFileReader,RilExtender,RilExtenderCommandsInterface,AtCommandInterface,CommandsInterfaceFactory,TelephonySeekServiceCommandsInterface,SIMRecords,Parcel,librilinject,CMDProcessor,lib__hijack.bin__.so,System.err,su
