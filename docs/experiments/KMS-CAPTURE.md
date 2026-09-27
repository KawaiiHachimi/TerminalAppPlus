# Existing virtual-screen capture (read-only experiment)

This probe captures the guest's existing DRM primary-plane framebuffer. It does
not start a second desktop, VNC/RDP session or host native-display service.

## Observed on PKB110 / current prebuilt Debian

- No /dev/fb0 is exposed; tty0's active VT is tty1.
- DRM card0 has an active 1280x720 linear XR24 framebuffer owned by gnome-shell.
- Guest-root DRM_IOCTL_MODE_GETFB2 returned a GEM handle; exporting it as a
  dma-buf and mapping it read-only produced the actual Debian 13 GDM login screen.
- The guest's kernel, compositor and login configuration were not changed.

`tools/experiments/kms-capture-server.py` is a Python standard-library prototype.
It requires guest administrator permission to inspect other processes' display
buffers and debugfs state. This is guest sudo, not Android host root. It supports
only the tested primary-plane linear XR24 layout; no cursor/overlay composition,
rotation, tiled GPU buffers, frame synchronization or protected content support.

The temporary server listens only on vsock port 7683, accepts only host CID 2,
and returns a length-prefixed PNG for each `F` request. No TCP/LAN port is opened.
The app connects via the VM-owned AVF connectVsock API. The debug-only
VmScreenProbeActivity bounds PNG sizes and displays the result in an ImageView.
This is a low-frame-rate proof of transport, not an optimized video pipeline.

## Run manually inside a prepared guest

Copy the helper into /tmp, then:

```sh
sudo systemd-run --unit=terminal-plus-kms-probe --collect \
  --property=RuntimeMaxSec=1800 \
  /usr/bin/python3 /tmp/terminal-plus-kms-server.py
```

Open the direct console drawer and choose the laboratory's read-only current-VM
screen. Stop the service with `sudo systemctl stop terminal-plus-kms-probe`.
The test service expires after 30 minutes and does not survive reboot.

## App versus image responsibilities

The App can provide viewing, transport, diagnostics and an optional helper
installation workflow. It cannot read an arbitrary guest's graphics memory
without a guest-side exporter or a host display API. Custom images do not need a
special OS build, but need compatible DRM support, Python for this prototype,
vsock, and permission to run the helper. Images without these prerequisites will
need another exporter. Nothing is silently installed into arbitrary guests.

References:
- AOSP libs/android_display_backend: original host Surface presentation path.
- https://www.kernel.org/doc/html/v6.15/gpu/drm-uapi.html : GETFB2 handle visibility
  requires DRM master or CAP_SYS_ADMIN; buffer access is distinct from serial I/O.

Status at branch split: standalone guest-frame export verified visually; Android
viewer builds, but its end-to-end live view has not yet been validated. Subsequent
branches compare AOSP-style input/rendering and a Termux:X11 route separately.

## AOSP-input branch

`codex/feat-aosp-kms-display` uses raw RGBA frames (no PNG encode/decode), a reused
Bitmap and hardware Canvas onto AOSP DisplaySurfaceView. InputForwarder is the
existing AOSP implementation, including touch scaling, captured pointer and key
forwarding. Surface layout preserves frame aspect ratio. The read-only menu label
is historical; this branch connects input as well.

Observed live on device: current GDM screen at 1280x720, approximately 8.1–8.2 fps
with the prototype's 100 ms server pacing. Input plumbing builds but comprehensive
mouse/IME/touch validation is still pending. This is not a zero-copy or finished
high-performance renderer; capture/transport overhead must be measured separately.

## Important correction: received frames are not necessarily changing frames

Follow-up on 2026-09-28 confirmed four captures two seconds apart had identical
SHA256 `2c50ab7a04915841d3b51e13f3a92d764ca9a01f43b840e37ffcd90330ace326`.
DRM framebuffer ID stayed 149. The captured GNOME greeter session c1 was
Active=no, while seat0 ActiveSession was a droid session created by the stock
interactive-shell display setup. `loginctl activate c1` did not make it active in
this test. Therefore the displayed ~8 fps measures repeated frame delivery, not
validated animated desktop updates. Renderer-performance comparison is premature.
A dynamically changing source and correct compositor/session ownership must be
verified before declaring the display/input path fully functional. No permanent
session/login configuration was changed to mask this limitation.

## Frozen-source fix

Root cause verified: AOSP's `/etc/profile.d/activate_display.sh` invokes
`enable_display` for each interactive ttyd shell, creating a dummy PAM login on
tty1. With GDM installed, these placeholders can make the real graphical session
inactive and drop its DRM master. Repeated capture then returns stale scanout.

The opt-in guest tool `fix-aosp-display-seat.py` backs up the known AOSP profile
and guards its bootstrap when display-manager.service is enabled or active.
It stops only matching stock transient sleep/PAM/tty1 placeholders, then restores
an unambiguous graphical session. A real VT transition is used only if logind's
same-VT activation leaves it inactive. No passwords, autologin or user names are
configured. This repair is specific to the prebuilt AOSP bootstrap, not a required
custom-image modification.

Capture now selects a framebuffer attached to an enabled, active CRTC and uses
DMA-BUF CPU read synchronization. The viewer counts content CRC changes separately
from frame delivery. A static image no longer masquerades as an animated 8-fps
desktop. Parser tests cover stale/inactive and absent scanout.

Verified repair on the running guest: real VT transition restored the greeter,
then the user graphical session remained ActiveSession=16 across fresh ttyd
connections. A short desktop notification changed captured PNG SHA256 from
`709a0f845efc1a3d110ab8dda1b16a796c51806e8bed9c57632ea63814f82121`
to `4ecd7a3aed14df2a6de78cea9e3cf62960d94f1f94a8ca15622957f8bf5dd897`.
The updated active-plane + DMA sync code also captured a 3,686,412-byte raw frame
successfully on device. Build, lint and five Python tests passed. The guest fix
was applied live; installing the newly built status-counter APK is not required
for the live seat repair and was deferred to preserve the active desktop session.

## Restored AOSP display UI

The AOSP branch restores DisplayController/DisplayScreen in the main Compose UI,
including fullscreen, pan/zoom, keyboard, touchpad/mouse capture, modifier keys and
clipboard hooks. The direct serial-console action remains separate. Advanced
settings again show display resolution; gfxstream remains gated by the original
device capability flag.

KmsDisplayProvider replaces only the restricted host Surface Binder path. It
reads bounded raw frames from the guest helper, renders to the original
DisplaySurfaceView, reports missing capture service visibly, retries connections,
and stops reading when the Activity stops or the composable is released. Actual
frame dimensions update touch mapping after resolution changes. The original
resolution setting drives VirtualMachine display resize and surface buffer size.

Build/lint passed and device UI inspection confirmed the restored controls and
fullscreen entry. Fullscreen/resolution combinations and external mouse behavior
still need broader hardware validation. The capture helper still omits separate
cursor/overlay planes; do not call this full native-display parity.
