/*
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
 *
 *
 *
 *  Based on smsdispatch.c from:
 *  Collin's Dynamic Dalvik Instrumentation Toolkit for Android
 *  Collin Mulliner <collin[at]mulliner.org>
 *
 *  (c) 2012,2013
 *
 *  License: LGPL v2.1
 *
 */

// TODO review https://developer.android.com/training/articles/perf-jni.html , if this is going to be "production quality"
// TODO look into this logcat message: "W/linker  (27134): librilinject.so has text relocations. This is wasting memory and is a security risk. Please fix."
// TODO gracefully handle failures like permissions errors, instead of crashing the phone process? or maybe crashing is the easy way to clean up?

#define _GNU_SOURCE
#include <stdio.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <sys/select.h>
#include <string.h>
#include <termios.h>
#include <pthread.h>
#include <sys/epoll.h>

#include <jni.h>
#include <stdlib.h>
#include <android/log.h>

#include "hook.h"
#include "dexstuff.h"
#include "dalvik_hook.h"
#include "base.h"

static struct hook_t eph;
static struct dexstuff_t d;
static struct dalvik_hook_t dalvikhook;

// switch for debug output of dalvikhook and dexstuff code
static int debug = 1;

#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "librilinject", __VA_ARGS__)

static jclass loadClassFromDex(JNIEnv *env, const char *classNameSlash, const char *classNameDot, const char *dexPath, const char *cachePath) {
	jclass clLoadedClass = (*env)->FindClass(env, classNameSlash);

	if (!clLoadedClass) {
		(*env)->ExceptionClear(env); // FindClass() complains if there's an exception already

		// Load my class with BaseDexClassLoader
		// See RilExtenderCommandsInterface.java:
		// new BaseDexClassLoader(rilExtenderDex.getAbsolutePath(), rilExtenderDexCacheDir, null, ClassLoader.getSystemClassLoader())

		jclass clFile = (*env)->FindClass(env, "java/io/File");
		jmethodID mFileConstructor = (*env)->GetMethodID(env, clFile, "<init>", "(Ljava/lang/String;)V");
		jobject obCacheDirFile = NULL;
		if (clFile && mFileConstructor) {
			obCacheDirFile = (*env)->NewObject(env, clFile, mFileConstructor, (*env)->NewStringUTF(env, cachePath));
			if ((*env)->ExceptionOccurred(env)) {
				ALOGD("new File() threw an exception");
				(*env)->ExceptionDescribe(env);
			}
		} else {
			ALOGD("Couldn't open cache File!");
		}

		jclass clDexClassLoader = (*env)->FindClass(env, "dalvik/system/BaseDexClassLoader");
		jmethodID mClassLoaderConstructor = (*env)->GetMethodID(env, clDexClassLoader, "<init>", "(Ljava/lang/String;Ljava/io/File;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
		jmethodID mGetSystemClassLoader = (*env)->GetStaticMethodID(env, clDexClassLoader, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
		jmethodID mLoadClass = (*env)->GetMethodID(env, clDexClassLoader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
		ALOGD("clDexClassLoader = %x, obCacheDirFile = %x", clDexClassLoader, obCacheDirFile);

		if (clDexClassLoader && mClassLoaderConstructor && mLoadClass && obCacheDirFile) {
			jobject classloaderobj = (*env)->NewObject(env, clDexClassLoader, mClassLoaderConstructor,
				(*env)->NewStringUTF(env, dexPath), obCacheDirFile, NULL,
				(*env)->CallStaticObjectMethod(env, clDexClassLoader, mGetSystemClassLoader));

			// XXX stingutf necesary?
			if (classloaderobj) {
				clLoadedClass = (*env)->CallObjectMethod(env, classloaderobj, mLoadClass, (*env)->NewStringUTF(env, classNameDot));
				if ((*env)->ExceptionOccurred(env)) {
					ALOGD("loadClass() threw an exception");
					(*env)->ExceptionDescribe(env);
				}
			} else {
				ALOGD("classloader object not found!");
			}
		} else {
			ALOGD("classloader/constructor not found!");
		}

		ALOGD("clLoadedClass = %x", clLoadedClass);
	}

	return clLoadedClass;
}

jclass clRilExtender = 0;
jmethodID mOnTransact = 0;

static jboolean onTransact_hook(JNIEnv *env, jobject obj, jint jiCode, jobject joData, jobject joReply, jint jiFlags) {
	jboolean returnValue = 0;

	if (!clRilExtender) {
		clRilExtender = loadClassFromDex(env, "net/scintill/simio/RilExtender", "net.scintill.simio.RilExtender",
			"/data/data/net.scintill.simfileseektest/app_rilextender/rilextender.dex", "/data/data/net.scintill.simfileseektest/app_rilextender-cache");
		if (clRilExtender) {
			clRilExtender = (*env)->NewGlobalRef(env, clRilExtender);
			// XXX delete ever? we're probably going to live forever, until the process dies
			mOnTransact = (*env)->GetStaticMethodID(env, clRilExtender, "onPhoneServiceTransact", "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z");
		}
	}

	if (clRilExtender && mOnTransact) {
		// XXX propagate exception?
		(*env)->ExceptionClear(env);
		returnValue = (*env)->CallStaticBooleanMethod(env, clRilExtender, mOnTransact, jiCode, joData, joReply, jiFlags);

		if (!obj) {
			ALOGD("failed to call rilinject class!");
		}

		if ((*env)->ExceptionOccurred(env)) {
			ALOGD("exception thrown");
			(*env)->ExceptionDescribe(env);
			returnValue = 0;
		}
	} else {
		ALOGD("class/method not found!");
	}

	// call original method
	if (!returnValue) {
		dalvik_prepare(&d, &dalvikhook, env);
		(*env)->ExceptionClear(env);
		returnValue = (*env)->CallBooleanMethod(env, obj, dalvikhook.mid, jiCode, joData, joReply, jiFlags);
		/*ALOGD("success calling : %s", dalvikhook.method_name);*/
		dalvik_postcall(&d, &dalvikhook);
	} else {
		/*ALOGD("suppressing call to the real method");*/
	}

	return returnValue;
}

static int my_epoll_wait(int epfd, struct epoll_event *events, int maxevents, int timeout) {
	int (*orig_epoll_wait)(int epfd, struct epoll_event *events, int maxevents, int timeout);
	orig_epoll_wait = (void*)eph.orig;
	// remove hook for epoll_wait
	hook_precall(&eph);

	// resolve symbols from DVM
	debug = 0; // dlopen logging is noisy
	dexstuff_resolv_dvm(&d);
	debug = 1;

	// hook
	dalvik_hook_setup(&dalvikhook, "Lcom/android/phone/PhoneInterfaceManager;", "onTransact", "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z", 5, onTransact_hook);
	//dalvikhook.debug_me = debug;
	dalvik_hook(&d, &dalvikhook);

	// call original function
	int res = orig_epoll_wait(epfd, events, maxevents, timeout);
	return res;
}


static void my_log(char *msg) {
	if (debug)
		ALOGD("%s", msg);
}

// set my_init as the entry point
void __attribute__ ((constructor)) my_init(void);

void my_init(void) {
	ALOGD("initializing");

	// set log function for  libbase (very important!)
	set_logfunction(my_log);
	// set log function for libdalvikhook (very important!)
	dalvikhook_set_logfunction(my_log);

	hook(&eph, getpid(), "libc.", "epoll_wait", my_epoll_wait, 0);
}
