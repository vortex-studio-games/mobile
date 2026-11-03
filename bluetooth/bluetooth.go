// Package bluetooth is a generic, host-agnostic Android Bluetooth Low Energy
// (BLE) central for Go, built on this fork's gomobile reverse bindings.
//
// It is deliberately UI-framework-agnostic: it reaches the Android BLE stack
// through the shared mobileinit JVM bridge, not through any one app framework's
// bootstrap. So every Android app built with this fork can use it:
//
//   - enjin / native gomobile apps get the JVM automatically (GoNativeActivity
//     registers it during JNI_OnLoad), so they call DefaultAdapter directly.
//   - Gio apps (e.g. the deck) own their own JVM, so they hand it to the bridge
//     once at startup with SetJVM(app.JavaVM(), app.AppContext()).
//
// The protocol spoken to a specific device (command frames, characteristic
// UUIDs, decoding) is the caller's concern — this package only moves bytes:
// scan, connect, discover a characteristic, write, and subscribe.
package bluetooth

import (
	"context"
	"errors"
)

// ErrUnsupported is returned by all entry points on non-Android platforms.
var ErrUnsupported = errors.New("bluetooth: BLE central is only supported on android")

// UUID is a Bluetooth attribute UUID. It accepts 16-bit shorthand ("ff01") or a
// full 128-bit form ("0000ff01-0000-1000-8000-00805f9b34fb").
type UUID string

// ScanResult is a single advertisement observed during a scan.
type ScanResult struct {
	Address string // device MAC address, e.g. "C0:FF:EE:00:11:22"
	Name    string // advertised local name, may be empty
	RSSI    int    // signal strength in dBm
}

// Adapter is the host's BLE radio in the central (client) role.
type Adapter struct{ impl adapterImpl }

// DefaultAdapter returns the host's BLE adapter. The process must already have a
// JVM registered with the gomobile bridge (automatic for native apps; via SetJVM
// for Gio apps). It errors if BLE is unavailable or the JVM is not yet set.
func DefaultAdapter() (*Adapter, error) {
	impl, err := defaultAdapter()
	if err != nil {
		return nil, err
	}
	return &Adapter{impl: impl}, nil
}

// Scan reports advertising devices to onResult until the context is cancelled or
// StopScan is called. onResult may be invoked from an internal goroutine.
func (a *Adapter) Scan(ctx context.Context, onResult func(ScanResult)) error {
	return a.impl.scan(ctx, onResult)
}

// StopScan ends an in-progress scan.
func (a *Adapter) StopScan() error { return a.impl.stopScan() }

// Connect establishes a GATT connection to the device with the given MAC
// address, blocking until connected or the context is done.
func (a *Adapter) Connect(ctx context.Context, address string) (*Device, error) {
	d, err := a.impl.connect(ctx, address)
	if err != nil {
		return nil, err
	}
	return &Device{impl: d}, nil
}

// Device is a connected GATT peripheral.
type Device struct{ impl deviceImpl }

// Characteristic resolves a characteristic by its service and characteristic
// UUID, discovering the GATT profile on first use.
func (d *Device) Characteristic(service, char UUID) (*Characteristic, error) {
	c, err := d.impl.characteristic(service, char)
	if err != nil {
		return nil, err
	}
	return &Characteristic{impl: c}, nil
}

// Disconnect closes the GATT connection.
func (d *Device) Disconnect() error { return d.impl.disconnect() }

// Characteristic is a single GATT characteristic on a connected device.
type Characteristic struct{ impl charImpl }

// Write performs a write-with-response to the characteristic.
func (c *Characteristic) Write(b []byte) error { return c.impl.write(b) }

// Subscribe enables notifications and delivers each value to onData until
// Unsubscribe is called. onData may be invoked from an internal goroutine.
func (c *Characteristic) Subscribe(onData func([]byte)) error { return c.impl.subscribe(onData) }

// Unsubscribe disables notifications for the characteristic.
func (c *Characteristic) Unsubscribe() error { return c.impl.unsubscribe() }

// Platform-specific implementations satisfy these interfaces. The public types
// above are thin, host-agnostic wrappers so the API surface is identical on
// every platform (the non-Android build returns ErrUnsupported).
type (
	adapterImpl interface {
		scan(context.Context, func(ScanResult)) error
		stopScan() error
		connect(context.Context, string) (deviceImpl, error)
	}
	deviceImpl interface {
		characteristic(service, char UUID) (charImpl, error)
		disconnect() error
	}
	charImpl interface {
		write([]byte) error
		subscribe(func([]byte)) error
		unsubscribe() error
	}
)
