package org.golang.app;

import android.app.Activity;
import android.app.NativeActivity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
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
