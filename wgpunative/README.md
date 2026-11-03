# wgpunative — supplemental wgpu-native slices for the iOS simulator

The engine renders through the `github.com/cogentcore/webgpu` Go binding, whose
vendored `libwgpu_native.a` set ships **no arm64 iOS-simulator slice** — only
device-arm64 (`aarch64-apple-ios`) and x86_64-simulator (`x86_64-apple-ios`).
On Apple-Silicon Macs the iOS *simulator* needs an `aarch64-apple-ios-sim`
build, and cgo cannot tell device from simulator (both are `GOOS=ios`/`arm64`),
so the binding always links the device slice and the simulator link fails with
`building for 'iOS-simulator', but linking in object file built for 'iOS'`.

This directory supplies the missing slice. `cmd/gomobile` (env.go,
`iosSimWgpuLib`) prepends it to `CGO_LDFLAGS` for `-target=iossimulator`/arm64,
so static-archive symbol resolution satisfies the wgpu symbols from here first
and the binding's device `-lwgpu_native` becomes a no-op.

## iossimulator/arm64/libwgpu_native.a

- Source: https://github.com/gfx-rs/wgpu-native @ `edcf4e7b8c47b0099e7874846fe0855e40a589f8`
  ("Add 16bit norm and NV12 textures", 2024-07-19). This is the exact commit
  `cogentcore/webgpu@v0.23.0` was generated against — its bundled `wgpu.h` and
  `webgpu.h` are byte-identical to this commit (modulo trailing newline), so the
  ABI matches the binding's generated Go structs.
- Rust target: `aarch64-apple-ios-sim`
- Build:
  ```sh
  git clone --recursive https://github.com/gfx-rs/wgpu-native.git
  cd wgpu-native && git checkout edcf4e7 && git submodule update --init --recursive
  rustup target add aarch64-apple-ios-sim
  CARGO_BUILD_TARGET=aarch64-apple-ios-sim \
    cargo build --release --config 'profile.release.strip="symbols"'
  # -> target/aarch64-apple-ios-sim/release/libwgpu_native.a  (Mach-O platform 7 = IOSSIMULATOR)
  ```

When the binding is bumped to a new `cogentcore/webgpu` version, rebuild this
slice from the wgpu-native commit matching that version's bundled headers.
