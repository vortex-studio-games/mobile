//go:build android

// JNI bridge between the Go BLE central and the GoBle Java helper (bundled in
// the GoNativeActivity dex). Go->Java calls go through goble_* helpers, invoked
// on a thread that mobileinit.RunOnJVM has attached (so env is valid). Java->Go
// callbacks are registered with RegisterNatives and forward to the //export'd Go
// functions in bluetooth_android.go.

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "_cgo_export.h"

#define GOBLE_CLASS "org/golang/app/GoBle"

static jclass gobleClass;
static jmethodID midStartScan, midStopScan, midConnect, midDiscover,
    midDisconnect, midWrite, midSubscribe, midUnsubscribe;

// --- Java -> Go native callbacks (registered via RegisterNatives) ---

static void nativeOnScanResult(JNIEnv *env, jclass cls, jstring addr,
                               jstring name, jint rssi) {
  const char *a = addr ? (*env)->GetStringUTFChars(env, addr, NULL) : NULL;
  const char *n = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
  goBleScanResult((char *)(a ? a : ""), (char *)(n ? n : ""), rssi);
  if (a) (*env)->ReleaseStringUTFChars(env, addr, a);
  if (n) (*env)->ReleaseStringUTFChars(env, name, n);
}

static void nativeOnConnectionChange(JNIEnv *env, jclass cls, jlong id,
                                     jint status, jint newState) {
  goBleConnectionChange(id, status, newState);
}

static void nativeOnServicesDiscovered(JNIEnv *env, jclass cls, jlong id,
                                       jint status) {
  goBleServicesDiscovered(id, status);
}

static void nativeOnCharWrite(JNIEnv *env, jclass cls, jlong id, jint status) {
  goBleCharWrite(id, status);
}

static void nativeOnCharChanged(JNIEnv *env, jclass cls, jlong id, jstring uuid,
                                jbyteArray value) {
  const char *u = uuid ? (*env)->GetStringUTFChars(env, uuid, NULL) : NULL;
  jsize n = value ? (*env)->GetArrayLength(env, value) : 0;
  jbyte *b = value ? (*env)->GetByteArrayElements(env, value, NULL) : NULL;
  goBleCharChanged(id, (char *)(u ? u : ""), (char *)b, n);
  if (b) (*env)->ReleaseByteArrayElements(env, value, b, JNI_ABORT);
  if (u) (*env)->ReleaseStringUTFChars(env, uuid, u);
}

// goble_register resolves the GoBle class + method IDs and wires the native
// callbacks. Must run once with an attached env before any other call. Returns 0
// on success, non-zero on failure.
//
// FindClass on a thread attached via AttachCurrentThread uses the *system*
// classloader, which cannot see app classes, so GoBle is loaded through the
// app's Context classloader instead.
int goble_register(uintptr_t envp, uintptr_t ctxp) {
  JNIEnv *env = (JNIEnv *)envp;
  jobject ctx = (jobject)ctxp;
  if (ctx == NULL) {
    return 1;
  }
  jclass ctxClass = (*env)->GetObjectClass(env, ctx);
  jmethodID getCL = (*env)->GetMethodID(env, ctxClass, "getClassLoader",
                                        "()Ljava/lang/ClassLoader;");
  jobject loader = (*env)->CallObjectMethod(env, ctx, getCL);
  if (loader == NULL) {
    (*env)->ExceptionClear(env);
    return 1;
  }
  jclass clClass = (*env)->FindClass(env, "java/lang/ClassLoader");
  jmethodID loadClass = (*env)->GetMethodID(
      env, clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
  jstring name = (*env)->NewStringUTF(env, "org.golang.app.GoBle");
  jclass cls = (jclass)(*env)->CallObjectMethod(env, loader, loadClass, name);
  (*env)->DeleteLocalRef(env, name);
  if (cls == NULL) {
    (*env)->ExceptionClear(env);
    return 1;
  }
  gobleClass = (jclass)(*env)->NewGlobalRef(env, cls);

  static const JNINativeMethod methods[] = {
      {"onScanResult", "(Ljava/lang/String;Ljava/lang/String;I)V",
       (void *)nativeOnScanResult},
      {"onConnectionChange", "(JII)V", (void *)nativeOnConnectionChange},
      {"onServicesDiscovered", "(JI)V", (void *)nativeOnServicesDiscovered},
      {"onCharWrite", "(JI)V", (void *)nativeOnCharWrite},
      {"onCharChanged", "(JLjava/lang/String;[B)V", (void *)nativeOnCharChanged},
  };
  if ((*env)->RegisterNatives(env, gobleClass, methods, 5) != 0) {
    (*env)->ExceptionClear(env);
    return 2;
  }

  midStartScan = (*env)->GetStaticMethodID(
      env, gobleClass, "startScan", "(Landroid/content/Context;)V");
  midStopScan = (*env)->GetStaticMethodID(
      env, gobleClass, "stopScan", "(Landroid/content/Context;)V");
  midConnect = (*env)->GetStaticMethodID(
      env, gobleClass, "connect",
      "(Landroid/content/Context;JLjava/lang/String;)V");
  midDiscover =
      (*env)->GetStaticMethodID(env, gobleClass, "discoverServices", "(J)V");
  midDisconnect =
      (*env)->GetStaticMethodID(env, gobleClass, "disconnect", "(J)V");
  midWrite = (*env)->GetStaticMethodID(
      env, gobleClass, "write",
      "(JLjava/lang/String;Ljava/lang/String;[B)Z");
  midSubscribe = (*env)->GetStaticMethodID(
      env, gobleClass, "subscribe",
      "(JLjava/lang/String;Ljava/lang/String;)Z");
  midUnsubscribe = (*env)->GetStaticMethodID(
      env, gobleClass, "unsubscribe",
      "(JLjava/lang/String;Ljava/lang/String;)Z");
  return 0;
}

// --- Go -> Java calls (env must be attached for the calling thread) ---

void goble_start_scan(uintptr_t envp, uintptr_t ctx) {
  JNIEnv *env = (JNIEnv *)envp;
  (*env)->CallStaticVoidMethod(env, gobleClass, midStartScan, (jobject)ctx);
}

void goble_stop_scan(uintptr_t envp, uintptr_t ctx) {
  JNIEnv *env = (JNIEnv *)envp;
  (*env)->CallStaticVoidMethod(env, gobleClass, midStopScan, (jobject)ctx);
}

void goble_connect(uintptr_t envp, uintptr_t ctx, long long id,
                   const char *addr) {
  JNIEnv *env = (JNIEnv *)envp;
  jstring jaddr = (*env)->NewStringUTF(env, addr);
  (*env)->CallStaticVoidMethod(env, gobleClass, midConnect, (jobject)ctx,
                               (jlong)id, jaddr);
  (*env)->DeleteLocalRef(env, jaddr);
}

void goble_discover(uintptr_t envp, long long id) {
  JNIEnv *env = (JNIEnv *)envp;
  (*env)->CallStaticVoidMethod(env, gobleClass, midDiscover, (jlong)id);
}

void goble_disconnect(uintptr_t envp, long long id) {
  JNIEnv *env = (JNIEnv *)envp;
  (*env)->CallStaticVoidMethod(env, gobleClass, midDisconnect, (jlong)id);
}

static jbyteArray newByteArray(JNIEnv *env, const char *data, int len) {
  jbyteArray arr = (*env)->NewByteArray(env, len);
  if (len > 0) {
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)data);
  }
  return arr;
}

int goble_write(uintptr_t envp, long long id, const char *svc, const char *chr,
                const char *data, int len) {
  JNIEnv *env = (JNIEnv *)envp;
  jstring jsvc = (*env)->NewStringUTF(env, svc);
  jstring jchr = (*env)->NewStringUTF(env, chr);
  jbyteArray jdata = newByteArray(env, data, len);
  jboolean ok = (*env)->CallStaticBooleanMethod(env, gobleClass, midWrite,
                                                (jlong)id, jsvc, jchr, jdata);
  (*env)->DeleteLocalRef(env, jdata);
  (*env)->DeleteLocalRef(env, jchr);
  (*env)->DeleteLocalRef(env, jsvc);
  return ok ? 1 : 0;
}

int goble_subscribe(uintptr_t envp, long long id, const char *svc,
                    const char *chr) {
  JNIEnv *env = (JNIEnv *)envp;
  jstring jsvc = (*env)->NewStringUTF(env, svc);
  jstring jchr = (*env)->NewStringUTF(env, chr);
  jboolean ok = (*env)->CallStaticBooleanMethod(env, gobleClass, midSubscribe,
                                                (jlong)id, jsvc, jchr);
  (*env)->DeleteLocalRef(env, jchr);
  (*env)->DeleteLocalRef(env, jsvc);
  return ok ? 1 : 0;
}

int goble_unsubscribe(uintptr_t envp, long long id, const char *svc,
                      const char *chr) {
  JNIEnv *env = (JNIEnv *)envp;
  jstring jsvc = (*env)->NewStringUTF(env, svc);
  jstring jchr = (*env)->NewStringUTF(env, chr);
  jboolean ok = (*env)->CallStaticBooleanMethod(env, gobleClass, midUnsubscribe,
                                                (jlong)id, jsvc, jchr);
  (*env)->DeleteLocalRef(env, jchr);
  (*env)->DeleteLocalRef(env, jsvc);
  return ok ? 1 : 0;
}
