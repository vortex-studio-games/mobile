# Go support for Mobile devices

> **Fork: `vortex.studio/mobile` — WebGPU/wgpu app layer.**
> This fork rewrites the `app` package's native rendering surface to drive a
> **WebGPU (wgpu) surface** instead of OpenGL ES, for both mobile platforms:
> - **Android** (`android_wgpu.go`): exposes the raw `ANativeWindow` (no EGL) so
>   the engine creates a Vulkan wgpu surface from it.
> - **iOS** (`darwin_ios.{go,m}`, `darwin_ios_wgpu.go`): the `UIView`'s backing
>   layer is a `CAMetalLayer`; its pointer is handed to Go via `setMetalLayer`,
>   and the engine creates a Metal wgpu surface from it. The GLES/`GLKView`/
>   `EAGLContext` path is removed (the engine never used GLES on iOS).
>
> **Consequence:** the upstream GL examples (e.g. `example/ivy/ios`, the GL
> `app` demos) no longer build for Android/iOS — this is intentional, to avoid
> maintaining a dual GL/WebGPU app layer. `cmd/gomobile` also gained: unsigned
> iOS-simulator builds, GLES3 headers on iOS, an arm64 iOS-simulator
> `libwgpu_native.a` slice (`wgpunative/`), and `-ldflags` pass-through for
> Apple builds. See `example/enjin-wgpu-ios/` for the iOS render spike.

[![Go Reference](https://pkg.go.dev/badge/golang.org/x/mobile.svg)](https://pkg.go.dev/golang.org/x/mobile)

The Go mobile repository holds packages and build tools for using Go on mobile platforms.

Package documentation as a starting point:

- [Building all-Go apps](https://golang.org/x/mobile/app)
- [Building libraries for SDK apps](https://golang.org/x/mobile/cmd/gobind)

![Caution image](doc/caution.png)

The Go Mobile project is experimental. Use this at your own risk.
While we are working hard to improve it, neither Google nor the Go
team can provide end-user support.

This is early work and installing the build system requires Go 1.5.
Follow the instructions on
[golang.org/wiki/Mobile](https://golang.org/wiki/Mobile)
to install the gomobile command, build the
[basic](https://golang.org/x/mobile/example/basic)
and the [bind](https://golang.org/x/mobile/example/bind) example apps.

--

Contributions to Go are appreciated. See https://go.dev/doc/contribute.

The git repository is https://go.googlesource.com/mobile.

* Bugs can be filed at the [Go issue tracker](https://go.dev/issue/new?title=x/mobile:+).
* Feature requests should preliminary be discussed on
[golang-nuts](https://groups.google.com/forum/#!forum/golang-nuts)
mailing list.
