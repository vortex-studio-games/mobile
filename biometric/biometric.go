// Package biometric is a host-agnostic biometric authentication gate for Go,
// built on this fork's gomobile reverse bindings.
//
// It follows the same shape as the bluetooth package: a portable API here, an
// Android implementation behind a build tag, and an ErrUnsupported fallback
// everywhere else so the package still builds and tests on desktop and CI.
//
// It authenticates a *person*, it does not produce a key. Callers must not treat
// success as authorisation to decrypt anything on its own — a biometric gate is a
// convenience in front of a secret the process already holds, or in front of one
// released by the platform keystore. Anything stronger needs a keystore-bound key,
// which is deliberately out of scope here.
package biometric

import (
	"context"
	"errors"
)

// ErrUnsupported means this host has no biometric hardware, no enrolment, or no
// implementation. Callers should hide the affordance rather than report a failure.
var ErrUnsupported = errors.New("biometric: not supported on this platform")

// ErrFailed means the user was prompted and did not authenticate: cancelled,
// dismissed, or too many bad attempts. Distinct from ErrUnsupported so a UI can tell
// "you can't do this here" apart from "that wasn't you".
var ErrFailed = errors.New("biometric: authentication failed")

// Prompt is the copy shown by the OS dialog. The OS owns the presentation; these are
// the only strings a caller controls.
type Prompt struct {
	// Title is the dialog heading, e.g. "Unlock boo".
	Title string
	// Subtitle is an optional second line.
	Subtitle string
	// Cancel labels the negative button. Empty uses the platform default.
	//
	// A negative button is mandatory on Android when no device-credential fallback
	// is allowed, so an empty value becomes "Cancel" rather than nothing.
	Cancel string
}

// Available reports whether biometric authentication can be attempted right now:
// hardware present, enrolled, and not locked out. It is cheap enough to call while
// building a screen, so a UI can decide whether to draw the affordance at all
// instead of discovering the answer after a tap.
func Available() bool { return available() }

// Authenticate shows the platform prompt and blocks until the user resolves it.
//
// Returns nil on success, ErrFailed on refusal, ErrUnsupported where unavailable,
// and ctx.Err() if the caller cancels first. Cancelling the context dismisses the
// dialog, which is what an auto-lock firing mid-prompt should do.
func Authenticate(ctx context.Context, p Prompt) error {
	if p.Title == "" {
		return errors.New("biometric: Prompt.Title is required")
	}
	if p.Cancel == "" {
		p.Cancel = "Cancel"
	}
	return authenticate(ctx, p)
}
