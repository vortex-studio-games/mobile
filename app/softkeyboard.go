// Copyright 2024 The Go Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

//go:build !android

package app

// ShowSoftKeyboard is a no-op on non-Android platforms.
// On Android, this shows the soft keyboard for text input.
func ShowSoftKeyboard() {
	// No-op on desktop/iOS
}

// HideSoftKeyboard is a no-op on non-Android platforms.
// On Android, this hides the soft keyboard.
func HideSoftKeyboard() {
	// No-op on desktop/iOS
}

// IMEInsetPx is 0 off Android: no other host reports a keyboard inset.
func IMEInsetPx() int { return 0 }

// BackPressed is always false off Android: no other host has a system back button.
func BackPressed() bool { return false }

// Finish is a no-op off Android.
func Finish() {}

// SetClipboard and GetClipboard are no-ops off Android: the clipboard there is reached
// through the activity, and no other host in this package has one.
func SetClipboard(string) {}

func GetClipboard() string { return "" }
