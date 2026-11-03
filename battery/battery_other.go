//go:build !android

package battery

// Level reports the battery percentage. Off-device there is no battery source
// wired up yet, so it reports unavailable. (A desktop build could read
// /sys/class/power_supply later.)
func Level() (percent int, ok bool) { return 0, false }
