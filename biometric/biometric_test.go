package biometric

import (
	"context"
	"errors"
	"testing"
)

func TestAuthenticateRequiresTitle(t *testing.T) {
	err := Authenticate(context.Background(), Prompt{})
	if err == nil {
		t.Fatal("a prompt with no title must be rejected")
	}
	if errors.Is(err, ErrUnsupported) {
		t.Fatal("missing title should fail validation before reaching the platform")
	}
}

func TestUnsupportedOffAndroid(t *testing.T) {
	if Available() {
		t.Skip("host reports biometrics available; not the fallback build")
	}
	err := Authenticate(context.Background(), Prompt{Title: "Unlock boo"})
	if !errors.Is(err, ErrUnsupported) {
		t.Fatalf("want ErrUnsupported, got %v", err)
	}
}

// ErrFailed and ErrUnsupported must stay distinguishable: the UI hides the
// affordance for one and reports a bad attempt for the other.
func TestErrorsAreDistinct(t *testing.T) {
	if errors.Is(ErrFailed, ErrUnsupported) || errors.Is(ErrUnsupported, ErrFailed) {
		t.Fatal("ErrFailed and ErrUnsupported must not match each other")
	}
}
