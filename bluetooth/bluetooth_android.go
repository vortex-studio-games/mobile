//go:build android

package bluetooth

/*
#include <stdint.h>
#include <stdlib.h>

int  goble_register(uintptr_t env, uintptr_t ctx);
void goble_start_scan(uintptr_t env, uintptr_t ctx);
void goble_stop_scan(uintptr_t env, uintptr_t ctx);
void goble_connect(uintptr_t env, uintptr_t ctx, long long id, const char* addr);
void goble_discover(uintptr_t env, long long id);
void goble_disconnect(uintptr_t env, long long id);
int  goble_write(uintptr_t env, long long id, const char* svc, const char* chr, const char* data, int len);
int  goble_subscribe(uintptr_t env, long long id, const char* svc, const char* chr);
int  goble_unsubscribe(uintptr_t env, long long id, const char* svc, const char* chr);
*/
import "C"

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"unsafe"

	"vortex.studio/mobile/internal/mobileinit"
)

// SetJVM registers the host's JavaVM and application Context with the shared
// gomobile bridge. Native gomobile apps (enjin) set this automatically during
// JNI_OnLoad; other hosts call it once at startup.
func SetJVM(javaVM, appContext uintptr) {
	mobileinit.SetCurrentContext(unsafe.Pointer(javaVM), appContext)
}

// bleRun runs fn on a JVM-attached thread (env is valid, ctx is the app
// Context jobject). Go->Java JNI calls must happen inside it.
func bleRun(fn func(env, ctx uintptr)) error {
	return mobileinit.RunOnJVM(func(_, env, ctx uintptr) error {
		fn(env, ctx)
		return nil
	})
}

var (
	bleMu       sync.Mutex
	bleRegister sync.Once
	bleRegErr   error
	scanHandler func(ScanResult)
	nextConnID  int64
	conns       = map[int64]*androidDevice{}
)

// ensureRegistered resolves the GoBle class + wires the native callbacks once.
func ensureRegistered() error {
	bleRegister.Do(func() {
		err := bleRun(func(env, ctx uintptr) {
			if C.goble_register(C.uintptr_t(env), C.uintptr_t(ctx)) != 0 {
				bleRegErr = errors.New("bluetooth: GoBle helper unavailable (is it in the dex?)")
			}
		})
		if err != nil {
			bleRegErr = err
		}
	})
	return bleRegErr
}

func lookupConn(id int64) *androidDevice {
	bleMu.Lock()
	defer bleMu.Unlock()
	return conns[id]
}

func defaultAdapter() (adapterImpl, error) {
	if err := ensureRegistered(); err != nil {
		return nil, err
	}
	return &androidAdapter{}, nil
}

type androidAdapter struct{}

func (a *androidAdapter) scan(_ context.Context, onResult func(ScanResult)) error {
	bleMu.Lock()
	scanHandler = onResult
	bleMu.Unlock()
	return bleRun(func(env, ctx uintptr) {
		C.goble_start_scan(C.uintptr_t(env), C.uintptr_t(ctx))
	})
}

func (a *androidAdapter) stopScan() error {
	err := bleRun(func(env, ctx uintptr) {
		C.goble_stop_scan(C.uintptr_t(env), C.uintptr_t(ctx))
	})
	bleMu.Lock()
	scanHandler = nil
	bleMu.Unlock()
	return err
}

func (a *androidAdapter) connect(ctx context.Context, address string) (deviceImpl, error) {
	if err := ensureRegistered(); err != nil {
		return nil, err
	}
	bleMu.Lock()
	nextConnID++
	id := nextConnID
	dev := &androidDevice{
		id:        id,
		connected: make(chan error, 1),
		services:  make(chan error, 1),
		notifies:  map[string]func([]byte){},
	}
	conns[id] = dev
	bleMu.Unlock()

	if err := bleRun(func(env, jctx uintptr) {
		caddr := C.CString(address)
		defer C.free(unsafe.Pointer(caddr))
		C.goble_connect(C.uintptr_t(env), C.uintptr_t(jctx), C.longlong(id), caddr)
	}); err != nil {
		dev.cleanup()
		return nil, err
	}

	// Wait for STATE_CONNECTED, then discover services and wait for those.
	if err := waitCtx(ctx, dev.connected); err != nil {
		dev.cleanup()
		return nil, err
	}
	if err := bleRun(func(env, _ uintptr) {
		C.goble_discover(C.uintptr_t(env), C.longlong(id))
	}); err != nil {
		dev.cleanup()
		return nil, err
	}
	if err := waitCtx(ctx, dev.services); err != nil {
		dev.cleanup()
		return nil, err
	}
	return dev, nil
}

func waitCtx(ctx context.Context, ch <-chan error) error {
	select {
	case err := <-ch:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}

type androidDevice struct {
	id        int64
	connected chan error
	services  chan error
	mu        sync.Mutex
	notifies  map[string]func([]byte)
}

func (d *androidDevice) cleanup() {
	bleMu.Lock()
	delete(conns, d.id)
	bleMu.Unlock()
	_ = bleRun(func(env, _ uintptr) {
		C.goble_disconnect(C.uintptr_t(env), C.longlong(d.id))
	})
}

func (d *androidDevice) characteristic(service, char UUID) (charImpl, error) {
	return &androidChar{dev: d, service: string(service), char: string(char)}, nil
}

func (d *androidDevice) disconnect() error {
	d.cleanup()
	return nil
}

type androidChar struct {
	dev     *androidDevice
	service string
	char    string
}

func (c *androidChar) write(b []byte) error {
	var ok C.int
	err := bleRun(func(env, _ uintptr) {
		csvc := C.CString(c.service)
		cchr := C.CString(c.char)
		defer C.free(unsafe.Pointer(csvc))
		defer C.free(unsafe.Pointer(cchr))
		var p *C.char
		if len(b) > 0 {
			p = (*C.char)(unsafe.Pointer(&b[0]))
		}
		ok = C.goble_write(C.uintptr_t(env), C.longlong(c.dev.id), csvc, cchr, p, C.int(len(b)))
	})
	if err != nil {
		return err
	}
	if ok == 0 {
		return errors.New("bluetooth: characteristic write failed")
	}
	return nil
}

func (c *androidChar) subscribe(onData func([]byte)) error {
	c.dev.mu.Lock()
	c.dev.notifies[normalizeUUID(c.char)] = onData
	c.dev.mu.Unlock()
	var ok C.int
	err := bleRun(func(env, _ uintptr) {
		csvc := C.CString(c.service)
		cchr := C.CString(c.char)
		defer C.free(unsafe.Pointer(csvc))
		defer C.free(unsafe.Pointer(cchr))
		ok = C.goble_subscribe(C.uintptr_t(env), C.longlong(c.dev.id), csvc, cchr)
	})
	if err != nil {
		return err
	}
	if ok == 0 {
		return errors.New("bluetooth: subscribe failed")
	}
	return nil
}

func (c *androidChar) unsubscribe() error {
	c.dev.mu.Lock()
	delete(c.dev.notifies, normalizeUUID(c.char))
	c.dev.mu.Unlock()
	return bleRun(func(env, _ uintptr) {
		csvc := C.CString(c.service)
		cchr := C.CString(c.char)
		defer C.free(unsafe.Pointer(csvc))
		defer C.free(unsafe.Pointer(cchr))
		C.goble_unsubscribe(C.uintptr_t(env), C.longlong(c.dev.id), csvc, cchr)
	})
}

// normalizeUUID lowercases and expands 16-bit shorthand to the full Bluetooth
// base UUID so subscribe keys match the values reported by Android.
func normalizeUUID(u string) string {
	u = strings.ToLower(u)
	if len(u) == 4 {
		return "0000" + u + "-0000-1000-8000-00805f9b34fb"
	}
	return u
}

// --- callbacks from Java (via the C bridge); may run on Binder threads ---

//export goBleScanResult
func goBleScanResult(addr, name *C.char, rssi C.int) {
	bleMu.Lock()
	h := scanHandler
	bleMu.Unlock()
	if h != nil {
		h(ScanResult{Address: C.GoString(addr), Name: C.GoString(name), RSSI: int(rssi)})
	}
}

//export goBleConnectionChange
func goBleConnectionChange(id C.longlong, status, newState C.int) {
	dev := lookupConn(int64(id))
	if dev == nil {
		return
	}
	switch {
	case newState == 2 && status == 0: // BluetoothProfile.STATE_CONNECTED
		trySend(dev.connected, nil)
	case newState == 0: // STATE_DISCONNECTED
		err := fmt.Errorf("bluetooth: disconnected (status %d)", int(status))
		trySend(dev.connected, err)
		trySend(dev.services, err)
	}
}

//export goBleServicesDiscovered
func goBleServicesDiscovered(id C.longlong, status C.int) {
	dev := lookupConn(int64(id))
	if dev == nil {
		return
	}
	var err error
	if status != 0 {
		err = fmt.Errorf("bluetooth: service discovery failed (status %d)", int(status))
	}
	trySend(dev.services, err)
}

//export goBleCharWrite
func goBleCharWrite(id C.longlong, status C.int) {
	// Write-with-response ack; nothing waits on it today.
}

//export goBleCharChanged
func goBleCharChanged(id C.longlong, uuid, data *C.char, n C.int) {
	dev := lookupConn(int64(id))
	if dev == nil {
		return
	}
	key := normalizeUUID(C.GoString(uuid))
	dev.mu.Lock()
	h := dev.notifies[key]
	dev.mu.Unlock()
	if h != nil {
		var b []byte
		if n > 0 {
			b = C.GoBytes(unsafe.Pointer(data), n)
		}
		h(b)
	}
}

func trySend(ch chan error, err error) {
	select {
	case ch <- err:
	default:
	}
}
