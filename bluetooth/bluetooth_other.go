//go:build !android

package bluetooth

import "context"

// On non-Android platforms there is no BLE central; every entry point reports
// ErrUnsupported so the package still builds and tests on desktop/CI.

func defaultAdapter() (adapterImpl, error) { return nil, ErrUnsupported }

// SetJVM is a no-op off Android.
func SetJVM(javaVM, appContext uintptr) {}

type unsupported struct{}

func (unsupported) scan(context.Context, func(ScanResult)) error        { return ErrUnsupported }
func (unsupported) stopScan() error                                     { return ErrUnsupported }
func (unsupported) connect(context.Context, string) (deviceImpl, error) { return nil, ErrUnsupported }
func (unsupported) characteristic(_, _ UUID) (charImpl, error)          { return nil, ErrUnsupported }
func (unsupported) disconnect() error                                   { return ErrUnsupported }
func (unsupported) write([]byte) error                                  { return ErrUnsupported }
func (unsupported) subscribe(func([]byte)) error                        { return ErrUnsupported }
func (unsupported) unsubscribe() error                                  { return ErrUnsupported }
