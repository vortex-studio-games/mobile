// Copyright 2014 The Go Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build android
// +build android

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include "_cgo_export.h"

#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, "Go", __VA_ARGS__)
#define LOG_FATAL(...) __android_log_print(ANDROID_LOG_FATAL, "Go", __VA_ARGS__)

static jclass current_class;

static jclass find_class(JNIEnv *env, const char *class_name) {
	jclass clazz = (*env)->FindClass(env, class_name);
	if (clazz == NULL) {
		(*env)->ExceptionClear(env);
		LOG_FATAL("cannot find %s", class_name);
		return NULL;
	}
	return clazz;
}

static jmethodID find_method(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
	jmethodID m = (*env)->GetMethodID(env, clazz, name, sig);
	if (m == 0) {
		(*env)->ExceptionClear(env);
		LOG_FATAL("cannot find method %s %s", name, sig);
		return 0;
	}
	return m;
}

static jmethodID find_static_method(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
	jmethodID m = (*env)->GetStaticMethodID(env, clazz, name, sig);
	if (m == 0) {
		(*env)->ExceptionClear(env);
		LOG_FATAL("cannot find method %s %s", name, sig);
		return 0;
	}
	return m;
}

static jmethodID key_rune_method;
static jmethodID ime_inset_method;
static jmethodID back_pressed_method;
static jmethodID set_clipboard_method;
static jmethodID bio_state_method;
static jmethodID bio_available_method;
static jmethodID bio_start_method;
static jmethodID bio_cancel_method;
static jmethodID bio_fp_method;
static jmethodID bio_hw_method;
static jmethodID bio_noenroll_method;
static jmethodID bio_openenroll_method;
static jmethodID set_orientation_method;
static jmethodID bio_has_method;
static jmethodID bio_forget_method;
static jmethodID bio_take_method;
static jmethodID bio_enroll_method;
static jmethodID bio_unlock_method;
static jmethodID get_clipboard_method;
static jmethodID finish_activity_method;
static jmethodID show_soft_keyboard_method;
static jmethodID hide_soft_keyboard_method;
static jobject current_activity;

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
	JNIEnv* env;
	if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
		return -1;
	}

	return JNI_VERSION_1_6;
}

static int main_running = 0;

// Entry point from our subclassed NativeActivity.
//
// By here, the Go runtime has been initialized (as we are running in
// -buildmode=c-shared) but the first time it is called, Go's main.main
// hasn't been called yet.
//
// The Activity may be created and destroyed multiple times throughout
// the life of a single process. Each time, onCreate is called.
void ANativeActivity_onCreate(ANativeActivity *activity, void* savedState, size_t savedStateSize) {
	if (!main_running) {
		JNIEnv* env = activity->env;

		// Note that activity->clazz is mis-named.
		current_class = (*env)->GetObjectClass(env, activity->clazz);
		current_class = (*env)->NewGlobalRef(env, current_class);
		key_rune_method = find_static_method(env, current_class, "getRune", "(III)I");
		ime_inset_method = find_static_method(env, current_class, "getImeInset", "()I");
		back_pressed_method = find_static_method(env, current_class, "getBackPressed", "()Z");
		set_clipboard_method = find_static_method(env, current_class, "setClipboard", "(Ljava/lang/String;)V");
		bio_state_method = find_static_method(env, current_class, "getBiometricState", "()I");
		bio_available_method = find_static_method(env, current_class, "biometricAvailable", "()Z");
		bio_start_method = find_static_method(env, current_class, "startBiometric",
			"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
		bio_cancel_method = find_static_method(env, current_class, "cancelBiometric", "()V");
		bio_fp_method = find_static_method(env, current_class, "fingerprintAvailable", "()Z");
		bio_hw_method = find_static_method(env, current_class, "fingerprintHardware", "()Z");
		bio_noenroll_method = find_static_method(env, current_class, "fingerprintNoneEnrolled", "()Z");
		bio_openenroll_method = find_static_method(env, current_class, "openBiometricEnroll", "()V");
		set_orientation_method = find_static_method(env, current_class, "setOrientation", "(I)V");
		bio_has_method = find_static_method(env, current_class, "bioHasSecret", "()Z");
		bio_forget_method = find_static_method(env, current_class, "bioForget", "()V");
		bio_take_method = find_static_method(env, current_class, "bioTakeSecret", "()Ljava/lang/String;");
		bio_enroll_method = find_static_method(env, current_class, "bioEnroll",
			"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
		bio_unlock_method = find_static_method(env, current_class, "bioUnlock",
			"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
		get_clipboard_method = find_static_method(env, current_class, "getClipboard", "()Ljava/lang/String;");
		finish_activity_method = find_method(env, current_class, "finishActivity", "()V");
		show_soft_keyboard_method = find_method(env, current_class, "showSoftKeyboard", "()V");
		hide_soft_keyboard_method = find_method(env, current_class, "hideSoftKeyboard", "()V");
		current_activity = (*env)->NewGlobalRef(env, activity->clazz);

		setCurrentContext(activity->vm, (*env)->NewGlobalRef(env, activity->clazz));

		// Set TMPDIR.
		jmethodID gettmpdir = find_method(env, current_class, "getTmpdir", "()Ljava/lang/String;");
		jstring jpath = (jstring)(*env)->CallObjectMethod(env, activity->clazz, gettmpdir, NULL);
		const char* tmpdir = (*env)->GetStringUTFChars(env, jpath, NULL);
		if (setenv("TMPDIR", tmpdir, 1) != 0) {
			LOG_INFO("setenv(\"TMPDIR\", \"%s\", 1) failed: %d", tmpdir, errno);
		}
		(*env)->ReleaseStringUTFChars(env, jpath, tmpdir);

		// Call the Go main.main.
		uintptr_t mainPC = (uintptr_t)dlsym(RTLD_DEFAULT, "main.main");
		if (!mainPC) {
			LOG_FATAL("missing main.main");
		}
		callMain(mainPC);
		main_running = 1;
	}

	// These functions match the methods on Activity, described at
	// http://developer.android.com/reference/android/app/Activity.html
	//
	// Note that onNativeWindowResized is not called on resize. Avoid it.
	// https://code.google.com/p/android/issues/detail?id=180645
	activity->callbacks->onStart = onStart;
	activity->callbacks->onResume = onResume;
	activity->callbacks->onSaveInstanceState = onSaveInstanceState;
	activity->callbacks->onPause = onPause;
	activity->callbacks->onStop = onStop;
	activity->callbacks->onDestroy = onDestroy;
	activity->callbacks->onWindowFocusChanged = onWindowFocusChanged;
	activity->callbacks->onNativeWindowCreated = onNativeWindowCreated;
	activity->callbacks->onNativeWindowRedrawNeeded = onNativeWindowRedrawNeeded;
	activity->callbacks->onNativeWindowDestroyed = onNativeWindowDestroyed;
	activity->callbacks->onInputQueueCreated = onInputQueueCreated;
	activity->callbacks->onInputQueueDestroyed = onInputQueueDestroyed;
	activity->callbacks->onConfigurationChanged = onConfigurationChanged;
	activity->callbacks->onLowMemory = onLowMemory;

	onCreate(activity);
}

int32_t fingerprintAvailable(JNIEnv* env) {
	return (int32_t)(*env)->CallStaticBooleanMethod(env, current_class, bio_fp_method);
}

void setOrientation(JNIEnv* env, int32_t mode) {
	(*env)->CallStaticVoidMethod(env, current_class, set_orientation_method, (jint)mode);
}

int32_t fingerprintHardware(JNIEnv* env) {
	return (int32_t)(*env)->CallStaticBooleanMethod(env, current_class, bio_hw_method);
}

int32_t fingerprintNoneEnrolled(JNIEnv* env) {
	return (int32_t)(*env)->CallStaticBooleanMethod(env, current_class, bio_noenroll_method);
}

void openBiometricEnroll(JNIEnv* env) {
	(*env)->CallStaticVoidMethod(env, current_class, bio_openenroll_method);
}

int32_t bioHasSecret(JNIEnv* env) {
	return (int32_t)(*env)->CallStaticBooleanMethod(env, current_class, bio_has_method);
}

void bioForget(JNIEnv* env) {
	(*env)->CallStaticVoidMethod(env, current_class, bio_forget_method);
}

char* bioTakeSecret(JNIEnv* env) {
	jstring s = (jstring)(*env)->CallStaticObjectMethod(env, current_class, bio_take_method);
	if (s == NULL) {
		return NULL;
	}
	const char* chars = (*env)->GetStringUTFChars(env, s, NULL);
	char* out = strdup(chars);
	(*env)->ReleaseStringUTFChars(env, s, chars);
	(*env)->DeleteLocalRef(env, s);
	return out;
}

void bioEnroll(JNIEnv* env, const char* t, const char* sub, const char* c, const char* secret) {
	jstring jt = (*env)->NewStringUTF(env, t);
	jstring js = (*env)->NewStringUTF(env, sub);
	jstring jc = (*env)->NewStringUTF(env, c);
	jstring jx = (*env)->NewStringUTF(env, secret);
	(*env)->CallStaticVoidMethod(env, current_class, bio_enroll_method, jt, js, jc, jx);
	(*env)->DeleteLocalRef(env, jt);
	(*env)->DeleteLocalRef(env, js);
	(*env)->DeleteLocalRef(env, jc);
	(*env)->DeleteLocalRef(env, jx);
}

void bioUnlock(JNIEnv* env, const char* t, const char* sub, const char* c) {
	jstring jt = (*env)->NewStringUTF(env, t);
	jstring js = (*env)->NewStringUTF(env, sub);
	jstring jc = (*env)->NewStringUTF(env, c);
	(*env)->CallStaticVoidMethod(env, current_class, bio_unlock_method, jt, js, jc);
	(*env)->DeleteLocalRef(env, jt);
	(*env)->DeleteLocalRef(env, js);
	(*env)->DeleteLocalRef(env, jc);
}

// biometricAvailable reports whether a prompt can be raised right now.
int32_t biometricAvailable(JNIEnv* env) {
	return (int32_t)(*env)->CallStaticBooleanMethod(env, current_class, bio_available_method);
}

// biometricState returns the outcome of the prompt in flight.
int32_t biometricState(JNIEnv* env) {
	return (int32_t)(*env)->CallStaticIntMethod(env, current_class, bio_state_method);
}

// startBiometric raises the prompt.
void startBiometric(JNIEnv* env, const char* title, const char* subtitle, const char* cancel) {
	jstring t = (*env)->NewStringUTF(env, title);
	jstring s = (*env)->NewStringUTF(env, subtitle);
	jstring c = (*env)->NewStringUTF(env, cancel);
	(*env)->CallStaticVoidMethod(env, current_class, bio_start_method, t, s, c);
	(*env)->DeleteLocalRef(env, t);
	(*env)->DeleteLocalRef(env, s);
	(*env)->DeleteLocalRef(env, c);
}

// cancelBiometric dismisses a prompt still on screen.
void cancelBiometric(JNIEnv* env) {
	(*env)->CallStaticVoidMethod(env, current_class, bio_cancel_method);
}

// setClipboard replaces the system clipboard contents.
void setClipboard(JNIEnv* env, const char* text) {
	jstring s = (*env)->NewStringUTF(env, text);
	(*env)->CallStaticVoidMethod(env, current_class, set_clipboard_method, s);
	(*env)->DeleteLocalRef(env, s);
}

// getClipboard returns the clipboard contents. The caller owns the returned buffer and
// must free it.
char* getClipboard(JNIEnv* env) {
	jstring s = (jstring)(*env)->CallStaticObjectMethod(env, current_class, get_clipboard_method);
	if (s == NULL) {
		return NULL;
	}
	const char* chars = (*env)->GetStringUTFChars(env, s, NULL);
	char* out = strdup(chars);
	(*env)->ReleaseStringUTFChars(env, s, chars);
	(*env)->DeleteLocalRef(env, s);
	return out;
}

// getBackPressed reports whether the back button was pressed since the last call.
int32_t getBackPressed(JNIEnv* env) {
	return (int32_t)(*env)->CallStaticBooleanMethod(env, current_class, back_pressed_method);
}

// finishActivity leaves the app.
void finishActivity(JNIEnv* env) {
	(*env)->CallVoidMethod(env, current_activity, finish_activity_method);
}

// getImeInset returns how many pixels of the window the keyboard covers.
int32_t getImeInset(JNIEnv* env) {
	return (int32_t)(*env)->CallStaticIntMethod(env, current_class, ime_inset_method);
}

int32_t getKeyRune(JNIEnv* env, AInputEvent* e) {
	return (int32_t)(*env)->CallStaticIntMethod(
		env,
		current_class,
		key_rune_method,
		AInputEvent_getDeviceId(e),
		AKeyEvent_getKeyCode(e),
		AKeyEvent_getMetaState(e)
	);
}

void showSoftKeyboard(JNIEnv* env) {
	if (current_activity != NULL && show_soft_keyboard_method != 0) {
		(*env)->CallVoidMethod(env, current_activity, show_soft_keyboard_method);
	}
}

void hideSoftKeyboard(JNIEnv* env) {
	if (current_activity != NULL && hide_soft_keyboard_method != 0) {
		(*env)->CallVoidMethod(env, current_activity, hide_soft_keyboard_method);
	}
}
