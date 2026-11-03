//go:build android

// JNI implementation of gobat_level: read the battery capacity via
// BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY). Uses only framework
// classes (visible to the system classloader on a RunOnJVM-attached thread), so
// no app Java/dex entry is required. Returns the percentage (0-100), or -1 if
// unavailable.

#include <jni.h>
#include <stdint.h>

int gobat_level(uintptr_t envp, uintptr_t ctxp) {
  JNIEnv *env = (JNIEnv *)envp;
  jobject ctx = (jobject)ctxp;
  if (ctx == NULL) return -1;

  jclass ctxClass = (*env)->GetObjectClass(env, ctx);
  jmethodID getService = (*env)->GetMethodID(
      env, ctxClass, "getSystemService",
      "(Ljava/lang/String;)Ljava/lang/Object;");
  if (getService == NULL) { (*env)->ExceptionClear(env); return -1; }

  jstring svcName = (*env)->NewStringUTF(env, "batterymanager");
  jobject bm = (*env)->CallObjectMethod(env, ctx, getService, svcName);
  (*env)->DeleteLocalRef(env, svcName);
  if (bm == NULL) { (*env)->ExceptionClear(env); return -1; }

  jclass bmClass = (*env)->FindClass(env, "android/os/BatteryManager");
  if (bmClass == NULL) { (*env)->ExceptionClear(env); return -1; }
  jmethodID getIntProperty =
      (*env)->GetMethodID(env, bmClass, "getIntProperty", "(I)I");
  if (getIntProperty == NULL) { (*env)->ExceptionClear(env); return -1; }

  // BATTERY_PROPERTY_CAPACITY = 4.
  jint level = (*env)->CallIntMethod(env, bm, getIntProperty, 4);
  if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return -1; }
  return (int)level;
}
