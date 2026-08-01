//go:build android

package biometric

import "context"

// The Android implementation is not written yet.
//
// It needs, mirroring the bluetooth package exactly:
//
//   - app/GoBiometric.java — a thin wrapper over
//     android.hardware.biometrics.BiometricPrompt (framework, API 28+, so no
//     androidx AAR is required and min SDK 30 already covers it). It must build
//     the prompt, call authenticate() with a CancellationSignal, and forward the
//     AuthenticationCallback results to native methods. A Java class is
//     unavoidable: AuthenticationCallback is an abstract class, so it cannot be
//     implemented with java.lang.reflect.Proxy.
//   - biometric/jni_android.c — resolve GoBiometric through the app Context's
//     classloader (FindClass on an attached thread sees only the system loader),
//     RegisterNatives for the callbacks, cache the method IDs.
//   - regeneration of the committed dex, since cmd/gomobile/gendex.go globs
//     app/*.java into a checked-in blob. Adding a Java file without regenerating
//     it silently ships the old class set.
//
// Until then Android behaves like every other host: ErrUnsupported, and callers
// fall back to the passphrase.

func available() bool { return false }

func authenticate(context.Context, Prompt) error { return ErrUnsupported }
