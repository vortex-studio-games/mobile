//go:build android

// Package battery reports the device battery charge. On Android it reads
// BatteryManager via JNI; it touches only framework classes (Context,
// BatteryManager), so unlike the BLE helper it needs no app Java in the dex.
package battery

/*
#include <stdint.h>
int gobat_level(uintptr_t env, uintptr_t ctx);
*/
import "C"

import "vortex.studio/mobile/internal/mobileinit"

// Level returns the battery charge as a percentage (0–100) and whether a reading
// was available.
func Level() (percent int, ok bool) {
	var v C.int = -1
	err := mobileinit.RunOnJVM(func(_, env, ctx uintptr) error {
		v = C.gobat_level(C.uintptr_t(env), C.uintptr_t(ctx))
		return nil
	})
	if err != nil || v < 0 || v > 100 {
		return 0, false
	}
	return int(v), true
}
