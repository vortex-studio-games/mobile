package org.golang.app;

import android.app.Activity;
import android.app.NativeActivity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricManager;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

public class GoNativeActivity extends NativeActivity {
	private static GoNativeActivity goNativeActivity;

	public GoNativeActivity() {
		super();
		goNativeActivity = this;
	}

	String getTmpdir() {
		return getCacheDir().getAbsolutePath();
	}

	static int getRune(int deviceId, int keyCode, int metaState) {
		try {
			int rune = KeyCharacterMap.load(deviceId).get(keyCode, metaState);
			if (rune == 0) {
				return -1;
			}
			return rune;
		} catch (KeyCharacterMap.UnavailableException e) {
			return -1;
		} catch (Exception e) {
			Log.e("Go", "exception reading KeyCharacterMap", e);
			return -1;
		}
	}

	private void load() {
		// Interestingly, NativeActivity uses a different method
		// to find native code to execute, avoiding
		// System.loadLibrary. The result is Java methods
		// implemented in C with JNIEXPORT (and JNI_OnLoad) are not
		// available unless an explicit call to System.loadLibrary
		// is done. So we do it here, borrowing the name of the
		// library from the same AndroidManifest.xml metadata used
		// by NativeActivity.
		try {
			ActivityInfo ai = getPackageManager().getActivityInfo(
					getIntent().getComponent(), PackageManager.GET_META_DATA);
			if (ai.metaData == null) {
				Log.e("Go", "loadLibrary: no manifest metadata found");
				return;
			}
			String libName = ai.metaData.getString("android.app.lib_name");
			System.loadLibrary(libName);
		} catch (Exception e) {
			Log.e("Go", "loadLibrary failed", e);
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		load();
		super.onCreate(savedInstanceState);

		// Start with the IME hidden: some devices auto-show one on launch even
		// though a native surface has no editable field. showSoftKeyboard()
		// overrides this when text input is genuinely needed.
		//
		// ADJUST_NOTHING is deliberate, and it is why android:windowSoftInputMode
		// in a manifest has no effect here. A NativeActivity's surface is not
		// resized for the IME anyway; letting the window try would resize the
		// swapchain on every keyboard toggle. The app is told the inset instead
		// (getImeInset) and keeps its own content clear of it.
		getWindow().setSoftInputMode(
				WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
						| WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
		watchImeInsets();

		// Enable rendering into display cutout area (notch) for API 28+
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			WindowManager.LayoutParams params = getWindow().getAttributes();
			params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
			getWindow().setAttributes(params);
		}

		// Go truly fullscreen: draw edge-to-edge and hide the status + navigation
		// bars (immersive). Re-applied on every focus gain (they transiently show
		// on a swipe from the edge).
		if (Build.VERSION.SDK_INT >= 30) {
			getWindow().setDecorFitsSystemWindows(false);
		}
		hideSystemBars();
	}

	// hideSystemBars hides the status and navigation bars in immersive-sticky
	// mode, so the app owns the whole screen and the bars only reappear
	// transiently on an edge swipe.
	private void hideSystemBars() {
		View decorView = getWindow().getDecorView();
		if (Build.VERSION.SDK_INT >= 30) {
			WindowInsetsController controller = decorView.getWindowInsetsController();
			if (controller != null) {
				controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
				controller.setSystemBarsBehavior(
						WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
			}
		} else {
			decorView.setSystemUiVisibility(
					View.SYSTEM_UI_FLAG_LAYOUT_STABLE
							| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
							| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_FULLSCREEN
							| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
		}
	}

	// ---- biometrics ----
	//
	// A Java class is unavoidable: BiometricPrompt.AuthenticationCallback is an
	// abstract class, so it cannot be implemented with a reflection Proxy. The result
	// is left in a field for Go to collect rather than sent back through a registered
	// native — the same polling shape the rest of this file already uses, and it
	// avoids RegisterNatives and a second classloader lookup entirely.
	//
	// android.hardware.biometrics is the framework API (28+), so no androidx AAR is
	// pulled in. minSdk here is 30, which covers it.
	static final int BIO_IDLE = 0;
	static final int BIO_PENDING = 1;
	static final int BIO_OK = 2;
	static final int BIO_FAILED = 3;
	static final int BIO_UNSUPPORTED = 4;

	private static int bioState = BIO_IDLE;
	private static CancellationSignal bioCancel;

	static int getBiometricState() {
		return bioState;
	}

	// fingerprintAvailable narrows biometricAvailable to devices with a fingerprint
	// sensor.
	//
	// It gates availability, not the prompt. BiometricPrompt has no fingerprint-only
	// selector: setAllowedAuthenticators takes a strength class, and on hardware where
	// face unlock is also Class 3 — Pixel 8 and 9, among others — a strong prompt will
	// accept a face as readily as a finger. Where face is Class 1, which is most other
	// hardware, it is excluded anyway because a weak authenticator cannot unlock a
	// Keystore key.
	//
	// So this answers "does this device have a fingerprint reader", which is what a
	// caller offering a fingerprint affordance needs to know. What the OS then accepts
	// at the prompt is the OS's to decide, and every authenticator it will accept is
	// hardware-backed and strong.
	static boolean fingerprintAvailable() {
		try {
			PackageManager pm = goNativeActivity.getPackageManager();
			if (pm == null || !pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
				return false;
			}
			return biometricAvailable();
		} catch (Exception e) {
			Log.e("Go", "fingerprintAvailable failed", e);
			return false;
		}
	}

	// biometricAvailable reports hardware present, enrolled, and not locked out.
	static boolean biometricAvailable() {
		try {
			if (Build.VERSION.SDK_INT < 29) {
				return false;
			}
			BiometricManager bm = (BiometricManager)
					goNativeActivity.getSystemService(Context.BIOMETRIC_SERVICE);
			if (bm == null) {
				return false;
			}
			int code = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
			if (code != BiometricManager.BIOMETRIC_SUCCESS) {
				// Worth naming: "no strong biometric" and "none enrolled yet" look
				// identical from the outside and mean very different things.
				Log.i("Go", "biometricAvailable: canAuthenticate=" + code
						+ " (0 ok, 1 unavailable, 11 none enrolled, 12 no hardware)");
			}
			return code == BiometricManager.BIOMETRIC_SUCCESS;
		} catch (Exception e) {
			Log.e("Go", "biometricAvailable failed", e);
			return false;
		}
	}

	// fingerprintHardware reports a sensor, whether or not anything is enrolled on it.
	//
	// This is the one that decides whether the app offers fingerprint unlock at all.
	// Enrolment is the person's to do and can be done at any time, so a phone with a
	// sensor and no finger on it is not a phone without the feature — it is a phone
	// with one instruction standing in the way. Hiding the option there leaves no way
	// to discover that instruction.
	static boolean fingerprintHardware() {
		try {
			PackageManager pm = goNativeActivity.getPackageManager();
			return pm != null && pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
		} catch (Exception e) {
			Log.e("Go", "fingerprintHardware failed", e);
			return false;
		}
	}

	// fingerprintNoneEnrolled reports the sensor being present with nothing on it.
	//
	// Told apart from every other failure because it is the only one the person can
	// fix, and the fix is somewhere else — see openBiometricEnroll.
	static boolean fingerprintNoneEnrolled() {
		try {
			if (!fingerprintHardware() || Build.VERSION.SDK_INT < 29) {
				return false;
			}
			BiometricManager bm = (BiometricManager)
					goNativeActivity.getSystemService(Context.BIOMETRIC_SERVICE);
			if (bm == null) {
				return false;
			}
			return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
					== BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED;
		} catch (Exception e) {
			Log.e("Go", "fingerprintNoneEnrolled failed", e);
			return false;
		}
	}

	// openBiometricEnroll sends the person to the system's own enrolment screen.
	//
	// ACTION_BIOMETRIC_ENROLL takes the authenticator class as an extra, so the screen
	// it opens is the one that ends with a credential this app's Keystore key will
	// accept. Enrolling a fingerprint invalidates any key already stored here, which is
	// the intended behaviour and the reason the passphrase never stops working.
	static void openBiometricEnroll() {
		goNativeActivity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					android.content.Intent i;
					if (Build.VERSION.SDK_INT >= 30) {
						i = new android.content.Intent(
								android.provider.Settings.ACTION_BIOMETRIC_ENROLL);
						i.putExtra(android.provider.Settings
										.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
								BiometricManager.Authenticators.BIOMETRIC_STRONG);
					} else {
						i = new android.content.Intent(
								android.provider.Settings.ACTION_SECURITY_SETTINGS);
					}
					goNativeActivity.startActivity(i);
				} catch (Exception e) {
					Log.e("Go", "openBiometricEnroll failed", e);
				}
			}
		});
	}

	static void startBiometric(final String title, final String subtitle, final String cancel) {
		bioState = BIO_PENDING;
		goNativeActivity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					bioCancel = new CancellationSignal();
					BiometricPrompt.Builder b = new BiometricPrompt.Builder(goNativeActivity)
							.setTitle(title == null ? "" : title)
							.setNegativeButton(
									cancel == null || cancel.isEmpty() ? "Cancel" : cancel,
									goNativeActivity.getMainExecutor(),
									new android.content.DialogInterface.OnClickListener() {
										@Override
										public void onClick(android.content.DialogInterface d, int w) {
											bioState = BIO_FAILED;
										}
									});
					if (subtitle != null && !subtitle.isEmpty()) {
						b.setSubtitle(subtitle);
					}
					b.build().authenticate(bioCancel, goNativeActivity.getMainExecutor(),
							new BiometricPrompt.AuthenticationCallback() {
								@Override
								public void onAuthenticationSucceeded(
										BiometricPrompt.AuthenticationResult r) {
									bioState = BIO_OK;
								}

								@Override
								public void onAuthenticationError(int code, CharSequence msg) {
									// Every error resolves the prompt. Telling "no
									// hardware" apart from "you cancelled" is the
									// caller's business through Available(), not here.
									bioState = BIO_FAILED;
								}
								// onAuthenticationFailed is a rejected finger, not a
								// resolution: the prompt stays up for another try.
							});
				} catch (Exception e) {
					Log.e("Go", "startBiometric failed", e);
					bioState = BIO_UNSUPPORTED;
				}
			}
		});
	}

	// cancelBiometric dismisses a prompt still on screen, for a caller whose context
	// was cancelled underneath it — an auto-lock firing mid-prompt, say.
	static void cancelBiometric() {
		try {
			if (bioCancel != null) {
				bioCancel.cancel();
				bioCancel = null;
			}
		} catch (Exception e) {
			Log.e("Go", "cancelBiometric failed", e);
		}
		bioState = BIO_FAILED;
	}

	// ---- biometric-wrapped secret ----
	//
	// A biometric prompt authenticates a person; it does not produce a key. To open
	// the vault without the passphrase, something has to hold key material across
	// launches and release it only to that person — which is the Keystore's job, not
	// a boolean this code could check and then choose to trust.
	//
	// The key is generated with setUserAuthenticationRequired, so every use of the
	// cipher has to be authorised by a prompt, and the prompt is given the Cipher as
	// a CryptoObject rather than being asked and believed. Patching out the check
	// gains an attacker nothing: the key is in hardware and will not perform the
	// operation unauthenticated.
	//
	// setInvalidatedByBiometricEnrollment means adding a new finger or face destroys
	// the key. The wrapped secret becomes unreadable and the app falls back to the
	// passphrase, which is the correct outcome: a new enrolment is a new person as
	// far as this is concerned.
	private static final String BIO_KEY = "boo-bio-dek";
	private static final String BIO_FILE = "boo-bio.bin";

	private static String bioSecret;      // base64, held only between success and collection
	private static byte[] bioPlainForWrap; // what enrol is waiting to encrypt

	static boolean bioHasSecret() {
		try {
			return new File(goNativeActivity.getFilesDir(), BIO_FILE).exists();
		} catch (Exception e) {
			return false;
		}
	}

	static void bioForget() {
		try {
			new File(goNativeActivity.getFilesDir(), BIO_FILE).delete();
			KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
			ks.load(null);
			ks.deleteEntry(BIO_KEY);
		} catch (Exception e) {
			Log.e("Go", "bioForget failed", e);
		}
	}

	// bioTakeSecret hands over the unwrapped secret exactly once.
	static String bioTakeSecret() {
		String s = bioSecret;
		bioSecret = null;
		return s == null ? "" : s;
	}

	private static SecretKey bioKey(boolean create) throws Exception {
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		SecretKey k = (SecretKey) ks.getKey(BIO_KEY, null);
		if (k != null || !create) {
			return k;
		}
		KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
		KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(BIO_KEY,
				KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setUserAuthenticationRequired(true)
				.setInvalidatedByBiometricEnrollment(true);
		g.init(b.build());
		return g.generateKey();
	}

	// bioEnroll wraps a secret behind the prompt. base64 in, nothing out.
	static void bioEnroll(final String title, final String subtitle, final String cancel,
			final String base64Secret) {
		bioState = BIO_PENDING;
		bioPlainForWrap = Base64.decode(base64Secret, Base64.NO_WRAP);
		goNativeActivity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
					c.init(Cipher.ENCRYPT_MODE, bioKey(true));
					promptWithCipher(title, subtitle, cancel, c, true);
				} catch (Exception e) {
					Log.e("Go", "bioEnroll failed", e);
					bioState = BIO_UNSUPPORTED;
				}
			}
		});
	}

	// bioUnlock unwraps it behind the prompt.
	static void bioUnlock(final String title, final String subtitle, final String cancel) {
		bioState = BIO_PENDING;
		goNativeActivity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					byte[] blob = readAll(new File(goNativeActivity.getFilesDir(), BIO_FILE));
					if (blob == null || blob.length < 13) {
						bioState = BIO_UNSUPPORTED;
						return;
					}
					int ivLen = blob[0] & 0xff;
					byte[] iv = new byte[ivLen];
					System.arraycopy(blob, 1, iv, 0, ivLen);
					byte[] ct = new byte[blob.length - 1 - ivLen];
					System.arraycopy(blob, 1 + ivLen, ct, 0, ct.length);

					SecretKey k = bioKey(false);
					if (k == null) {
						bioState = BIO_UNSUPPORTED;
						return;
					}
					Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
					c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
					bioPendingCipherText = ct;
					promptWithCipher(title, subtitle, cancel, c, false);
				} catch (Exception e) {
					// A destroyed key lands here: a new enrolment invalidated it, and
					// the passphrase is the way in.
					Log.e("Go", "bioUnlock failed", e);
					bioState = BIO_UNSUPPORTED;
				}
			}
		});
	}

	private static byte[] bioPendingCipherText;

	private static void promptWithCipher(String title, String subtitle, String cancel,
			final Cipher cipher, final boolean wrapping) {
		bioCancel = new CancellationSignal();
		BiometricPrompt.Builder b = new BiometricPrompt.Builder(goNativeActivity)
				.setTitle(title == null ? "" : title)
				// Strong only. A Keystore key with setUserAuthenticationRequired can
				// only be unlocked by a Class 3 authenticator, so asking for anything
				// less would raise a prompt that cannot finish the job.
				.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
				.setNegativeButton(cancel == null || cancel.isEmpty() ? "Cancel" : cancel,
						goNativeActivity.getMainExecutor(),
						new android.content.DialogInterface.OnClickListener() {
							@Override
							public void onClick(android.content.DialogInterface d, int w) {
								bioState = BIO_FAILED;
							}
						});
		if (subtitle != null && !subtitle.isEmpty()) {
			b.setSubtitle(subtitle);
		}
		b.build().authenticate(new BiometricPrompt.CryptoObject(cipher), bioCancel,
				goNativeActivity.getMainExecutor(),
				new BiometricPrompt.AuthenticationCallback() {
					@Override
					public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
						try {
							Cipher c = r.getCryptoObject().getCipher();
							if (wrapping) {
								byte[] iv = c.getIV();
								byte[] ct = c.doFinal(bioPlainForWrap);
								bioPlainForWrap = null;
								byte[] out = new byte[1 + iv.length + ct.length];
								out[0] = (byte) iv.length;
								System.arraycopy(iv, 0, out, 1, iv.length);
								System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
								writeAll(new File(goNativeActivity.getFilesDir(), BIO_FILE), out);
							} else {
								byte[] plain = c.doFinal(bioPendingCipherText);
								bioPendingCipherText = null;
								bioSecret = Base64.encodeToString(plain, Base64.NO_WRAP);
							}
							bioState = BIO_OK;
						} catch (Exception e) {
							Log.e("Go", "biometric cipher failed", e);
							bioState = BIO_FAILED;
						}
					}

					@Override
					public void onAuthenticationError(int code, CharSequence msg) {
						bioState = BIO_FAILED;
					}
				});
	}

	private static byte[] readAll(File f) {
		try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
			byte[] out = new byte[(int) f.length()];
			int n = 0;
			while (n < out.length) {
				int r = in.read(out, n, out.length - n);
				if (r < 0) {
					break;
				}
				n += r;
			}
			return out;
		} catch (Exception e) {
			return null;
		}
	}

	private static void writeAll(File f, byte[] data) {
		try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
			out.write(data);
		} catch (Exception e) {
			Log.e("Go", "bio write failed", e);
		}
	}

	// ---- clipboard ----
	//
	// Reachable from any thread: ClipboardManager is a system service and these are
	// binder calls. Reading is restricted to the focused app on Android 10 and later,
	// which is exactly when an app has any business reading it.

	static void setClipboard(String text) {
		try {
			ClipboardManager cm = (ClipboardManager)
					goNativeActivity.getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm != null) {
				cm.setPrimaryClip(ClipData.newPlainText("", text == null ? "" : text));
			}
		} catch (Exception e) {
			Log.e("Go", "setClipboard failed", e);
		}
	}

	static String getClipboard() {
		try {
			ClipboardManager cm = (ClipboardManager)
					goNativeActivity.getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm == null) {
				return "";
			}
			ClipData clip = cm.getPrimaryClip();
			if (clip == null || clip.getItemCount() == 0) {
				return "";
			}
			CharSequence t = clip.getItemAt(0).coerceToText(goNativeActivity);
			return t == null ? "" : t.toString();
		} catch (Exception e) {
			Log.e("Go", "getClipboard failed", e);
			return "";
		}
	}

	// backPressed latches a press of the system back button or gesture.
	//
	// A NativeActivity finishes on back by default, which for an app with screens of
	// its own means the whole thing closes instead of going back one. The activity
	// keeps the press instead and Go collects it; if the app has nothing left to go
	// back to it calls finishActivity, so back still leaves at the top level.
	private static boolean backPressed = false;

	static boolean getBackPressed() {
		boolean v = backPressed;
		backPressed = false;
		return v;
	}

	@Override
	public void onBackPressed() {
		// Deliberately not calling super: that is what finishes the activity.
		backPressed = true;
	}

	// finishActivity leaves the app, for when Go decides the back press has nowhere
	// left to go.
	void finishActivity() {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				finish();
			}
		});
	}

	// imeInsetPx is how much of the window the keyboard currently covers.
	//
	// A NativeActivity is not resized when the IME appears: the system reports it as
	// an inset and leaves the surface alone. That is the right shape for a GPU
	// surface — resizing a swapchain on every keyboard toggle is expensive — but it
	// means the app has to keep its own content clear of the covered strip, and it
	// cannot do that without being told how tall the strip is.
	private static int imeInsetPx = 0;

	// getImeInset is read from native through JNI; see getImeInset in android.c.
	static int getImeInset() {
		return imeInsetPx;
	}

	// keyboardWanted tracks whether the app has asked for the IME, so the
	// lifecycle hides below do not fight a request the app has already made.
	private static boolean keyboardWanted = false;

	// watchImeInsets keeps imeInsetPx current. Registered once, in onCreate.
	private void watchImeInsets() {
		if (Build.VERSION.SDK_INT < 30) {
			return;
		}
		getWindow().getDecorView().setOnApplyWindowInsetsListener(
				new View.OnApplyWindowInsetsListener() {
					@Override
					public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
						imeInsetPx = insets.getInsets(WindowInsets.Type.ime()).bottom;
						return v.onApplyWindowInsets(insets);
					}
				});
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			hideSystemBars();
			if (keyboardWanted) {
				showSoftKeyboard();
				return;
			}
			// Belt-and-suspenders: explicitly dismiss the IME on every focus
			// gain. STATE_ALWAYS_HIDDEN alone isn't enough on some devices
			// where the IME has been brought up by a prior activity instance.
			try {
				InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
				if (imm != null) {
					View view = getWindow().getDecorView();
					if (view != null) {
						imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
					}
				}
			} catch (Exception e) {
				Log.e("Go", "onWindowFocusChanged hide IME failed", e);
			}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Also hide on every resume — covers the launch path and returning from
		// background.
		try {
			InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null) {
				View view = getWindow().getDecorView();
				if (view != null) {
					imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
				}
			}
		} catch (Exception e) {
			Log.e("Go", "onResume hide IME failed", e);
		}
	}

	// Show the soft keyboard
	void showSoftKeyboard() {
		keyboardWanted = true;
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					// The window has to be able to take focus before anything
					// will show a keyboard over it.
					View decorView = getWindow().getDecorView();
					decorView.setFocusable(true);
					decorView.setFocusableInTouchMode(true);
					decorView.requestFocus();

					// API 30+ is the supported path. toggleSoftInput with
					// SHOW_FORCED, which this used to call, is deprecated and
					// does nothing at all on recent releases — the request was
					// made and logged, and no keyboard ever appeared.
					WindowInsetsController ctrl = decorView.getWindowInsetsController();
					if (Build.VERSION.SDK_INT >= 30 && ctrl != null) {
						ctrl.show(WindowInsets.Type.ime());
						Log.i("Go", "showSoftKeyboard: WindowInsetsController.show(ime)");
						return;
					}
					InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
					if (imm != null) {
						imm.showSoftInput(decorView, InputMethodManager.SHOW_IMPLICIT);
						Log.i("Go", "showSoftKeyboard: showSoftInput called");
					}
				} catch (Exception e) {
					Log.e("Go", "showSoftKeyboard failed", e);
				}
			}
		});
	}

	// Hide the soft keyboard
	void hideSoftKeyboard() {
		keyboardWanted = false;
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
					if (imm != null) {
						View view = getWindow().getDecorView();
						if (view != null) {
							// Use hideSoftInputFromWindow only. Do NOT call
							// toggleSoftInput here — toggle SHOWS the IME when
							// it is already hidden, which is exactly the
							// opposite of what we want.
							imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
						}
						Log.i("Go", "hideSoftKeyboard: called");
					}
				} catch (Exception e) {
					Log.e("Go", "hideSoftKeyboard failed", e);
				}
			}
		});
	}
}
