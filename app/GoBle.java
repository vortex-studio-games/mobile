// Copyright 2026 The Vortex Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package org.golang.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// GoBle is the Java side of the native BLE central. It does the Java-only work
// that JNI from C can't reasonably do — subclassing the abstract ScanCallback
// and BluetoothGattCallback — and forwards every event to Go through the static
// native methods below. Go drives it (scan/connect/write/...) through JNI; the
// callbacks flow back the other way. It is a sibling of GoNativeActivity so it
// rides in the same bundled dex; no reverse bindings.
//
// Connections are keyed by a Go-assigned id so multiple peripherals can be
// tracked, and so Go never holds a Java object reference.
public class GoBle {
	// Native callbacks, implemented in C (the bluetooth package's cgo) and
	// forwarded to Go. They may be invoked on Binder threads.
	private static native void onScanResult(String address, String name, int rssi);
	private static native void onConnectionChange(long id, int status, int newState);
	private static native void onServicesDiscovered(long id, int status);
	private static native void onCharWrite(long id, int status);
	private static native void onCharChanged(long id, String uuid, byte[] value);

	private static final String CCCD = "00002902-0000-1000-8000-00805f9b34fb";

	private static final Map<Long, BluetoothGatt> gatts = new HashMap<Long, BluetoothGatt>();
	private static ScanCallback scanCb;

	private static BluetoothAdapter adapter(Context ctx) {
		BluetoothManager mgr =
				(BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
		if (mgr != null) {
			return mgr.getAdapter();
		}
		return BluetoothAdapter.getDefaultAdapter();
	}

	// --- scanning ---

	static synchronized void startScan(Context ctx) {
		BluetoothAdapter a = adapter(ctx);
		if (a == null) {
			return;
		}
		BluetoothLeScanner scanner = a.getBluetoothLeScanner();
		if (scanner == null) {
			return;
		}
		scanCb = new ScanCallback() {
			@Override
			public void onScanResult(int callbackType, ScanResult result) {
				BluetoothDevice d = result.getDevice();
				if (d == null) {
					return;
				}
				GoBle.onScanResult(d.getAddress(), d.getName(), result.getRssi());
			}
		};
		scanner.startScan(scanCb);
	}

	static synchronized void stopScan(Context ctx) {
		BluetoothAdapter a = adapter(ctx);
		if (a != null && scanCb != null) {
			BluetoothLeScanner scanner = a.getBluetoothLeScanner();
			if (scanner != null) {
				scanner.stopScan(scanCb);
			}
		}
		scanCb = null;
	}

	// --- connections ---

	static synchronized void connect(Context ctx, final long id, String address) {
		BluetoothAdapter a = adapter(ctx);
		if (a == null) {
			onConnectionChange(id, -1, 0);
			return;
		}
		BluetoothDevice dev = a.getRemoteDevice(address);
		BluetoothGattCallback cb = new BluetoothGattCallback() {
			@Override
			public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
				gatts.put(id, g);
				GoBle.onConnectionChange(id, status, newState);
			}

			@Override
			public void onServicesDiscovered(BluetoothGatt g, int status) {
				GoBle.onServicesDiscovered(id, status);
			}

			@Override
			public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
				GoBle.onCharWrite(id, status);
			}

			@Override
			public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
				GoBle.onCharChanged(id, c.getUuid().toString(), c.getValue());
			}
		};
		dev.connectGatt(ctx, false, cb);
	}

	static synchronized void discoverServices(long id) {
		BluetoothGatt g = gatts.get(id);
		if (g != null) {
			g.discoverServices();
		}
	}

	static synchronized void disconnect(long id) {
		BluetoothGatt g = gatts.remove(id);
		if (g != null) {
			g.disconnect();
			g.close();
		}
	}

	// --- characteristics ---

	private static BluetoothGattCharacteristic characteristic(long id, String service, String chr) {
		BluetoothGatt g = gatts.get(id);
		if (g == null) {
			return null;
		}
		UUID cu = UUID.fromString(expand(chr));
		// A known service narrows the lookup; an empty service searches every
		// discovered service (some controllers don't advertise a stable service
		// UUID, so the caller may only know the characteristic).
		if (service != null && service.length() > 0) {
			BluetoothGattService s = g.getService(UUID.fromString(expand(service)));
			return s != null ? s.getCharacteristic(cu) : null;
		}
		for (BluetoothGattService s : g.getServices()) {
			BluetoothGattCharacteristic c = s.getCharacteristic(cu);
			if (c != null) {
				return c;
			}
		}
		return null;
	}

	static synchronized boolean write(long id, String service, String chr, byte[] data) {
		BluetoothGatt g = gatts.get(id);
		BluetoothGattCharacteristic c = characteristic(id, service, chr);
		if (g == null || c == null) {
			return false;
		}
		c.setValue(data);
		c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
		return g.writeCharacteristic(c);
	}

	static synchronized boolean subscribe(long id, String service, String chr) {
		BluetoothGatt g = gatts.get(id);
		BluetoothGattCharacteristic c = characteristic(id, service, chr);
		if (g == null || c == null) {
			return false;
		}
		g.setCharacteristicNotification(c, true);
		BluetoothGattDescriptor cccd = c.getDescriptor(UUID.fromString(CCCD));
		if (cccd != null) {
			cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
			g.writeDescriptor(cccd);
		}
		return true;
	}

	static synchronized boolean unsubscribe(long id, String service, String chr) {
		BluetoothGatt g = gatts.get(id);
		BluetoothGattCharacteristic c = characteristic(id, service, chr);
		if (g == null || c == null) {
			return false;
		}
		g.setCharacteristicNotification(c, false);
		return true;
	}

	// expand turns a 16-bit shorthand ("ff01") into a full Bluetooth base UUID.
	private static String expand(String u) {
		if (u.length() == 4) {
			return "0000" + u + "-0000-1000-8000-00805f9b34fb";
		}
		return u;
	}
}
