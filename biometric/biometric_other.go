//go:build !android

package biometric

import "context"

// Off Android there is no biometric gate, so every entry point reports
// ErrUnsupported and the package still builds and tests on desktop and CI.
//
// Desktop is not merely unimplemented: the design only ever promises Touch ID on
// macOS, and the passphrase path (flow B5, "use passphrase instead") is the
// documented fallback everywhere else.

func available() bool { return false }

func authenticate(context.Context, Prompt) error { return ErrUnsupported }
