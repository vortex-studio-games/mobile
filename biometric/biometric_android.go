//go:build android

package biometric

import (
	"context"
	"time"

	"vortex.studio/mobile/app"
)

// The Android implementation goes through the activity rather than owning its own JNI.
//
// The original plan here was a separate GoBiometric.java plus a jni_android.c that
// resolved it through the app Context's classloader and registered natives for the
// callbacks. That is all avoidable: the app package already holds a classloader-resolved
// reference to the activity, and the callback can leave its answer in a field for this
// side to collect. A Java class is still unavoidable — BiometricPrompt's
// AuthenticationCallback is abstract, so no reflection Proxy can stand in for it — but
// it lives with the rest of the activity's shims instead of in a second one.
//
// It authenticates a person and produces no key, exactly as the package doc says.

// pollInterval is how often the prompt's outcome is checked. A person takes a moment to
// present a finger; this is well below anything noticeable and costs a JNI call.
const pollInterval = 50 * time.Millisecond

func available() bool { return app.BiometricAvailable() }

func authenticate(ctx context.Context, p Prompt) error {
	if !app.BiometricAvailable() {
		return ErrUnsupported
	}
	app.BiometricStart(p.Title, p.Subtitle, p.Cancel)

	t := time.NewTicker(pollInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			// Dismiss the dialog rather than leaving it over an app that has moved
			// on — an auto-lock firing mid-prompt is the case this exists for.
			app.BiometricCancel()
			return ctx.Err()
		case <-t.C:
			switch app.BiometricState() {
			case app.BioOK:
				return nil
			case app.BioFailed:
				return ErrFailed
			case app.BioUnsupported:
				return ErrUnsupported
			}
		}
	}
}
