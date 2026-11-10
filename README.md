# CamLink

A small Android app for copying photos and videos from a **Samsung WB1100F**
(or any Samsung Wi-Fi camera from the MobileLink era) to a modern phone —
including a Galaxy S25 Ultra on Android 15/16, where the old Samsung SMART
CAMERA / MobileLink app no longer works.

CamLink is a native Kotlin app. It speaks the camera's existing **DLNA/UPnP**
protocol. It never installs firmware, never writes to the camera and never
deletes anything on the card. Files are downloaded with plain HTTP GET and saved
to the phone's gallery.

## How it works

Samsung Wi-Fi cameras of this generation publish a standard UPnP
**MediaServer** with a **ContentDirectory** service. That is the same service
Windows Explorer and smart TVs use.

1. CamLink discovers the camera with SSDP (`M-SEARCH` to `239.255.255.250:1900`).
2. It fetches the camera's device description and finds the ContentDirectory
   control URL. The description is requested raw with `Connection: close`
   (HTTP/1.1 first, then HTTP/1.0), because the camera's Myriad-CS server
   sometimes leaves the response open without a `Content-Length`.
3. It calls `ContentDirectory.Browse` over SOAP, starting at object `0` and
   walking containers until the card is listed.
4. Every `<item>` has one or more `<res>` URLs. CamLink picks the largest
   non-thumbnail resource, then downloads it over HTTP with up to six parallel
   connections. It starts with two and adds streams only while the measured
   throughput is still rising, so a small camera server is not overloaded.
5. Images go to `Pictures/CamLink`, videos to `Movies/CamLink`, using
   MediaStore. Files whose names are already in those folders are skipped.

In **MobileLink** mode the camera creates its own access point and its HTTP
server answers `401 Unauthorised` until a client introduces itself the way the
Samsung app does. CamLink sends that introduction (plain `GET /smp_2_` with
`User-Agent: SEC_RVF_ML_<mac>` and `Access-Method: manual`), waits for the
camera's **allow this device** prompt, then retries the normal DLNA requests.
This is read-only: no request writes to the camera.

If SSDP is filtered, CamLink also probes the Wi-Fi gateway and common camera
addresses, and you can type the address by hand (usually `192.168.101.1` in
MobileLink mode).

## Install

The debug APK is at:

```
dist/CamLink-1.5-debug.apk
```

Copy it to the phone and open it (allow installing unknown apps when prompted),
or with the phone connected over USB:

```powershell
adb install -r dist/CamLink-1.5-debug.apk
```

Requirements: Android 10 or newer (`minSdk 29`). The release targets Android 14
(`targetSdk 34`) deliberately, so Android 16's local-network permission gate
does not apply.

## Using it with the WB1100F

The camera has two DLNA modes and CamLink works with both.

### MobileLink (the camera's own Wi-Fi)

1. On the camera: rotate the mode dial to **Wi-Fi** → **MobileLink**. The
   camera creates its own access point; its SSID is on the camera screen
   (`AP_SSC_WB1100F_…` or similar).
2. On the phone: join that network. If Android says it has no internet, choose
   **Keep connection**.
3. Open CamLink and tap **Find camera**. CamLink sends the same introduction
   the Samsung app uses (`GET /smp_2_` with `SEC_RVF_ML_…` /
   `Access-Method: manual`), which makes the camera show an **allow this device**
   prompt. Choose **Allow / OK** on the camera.
4. If it still refuses, stay on the MobileLink screen and tap **Find camera**
   again; the prompt can take a few seconds to appear.

### Samsung Link (shared Wi-Fi)

1. On the camera: **Wi-Fi** → **Samsung Link** → **Nearby devices**. The camera
   joins the same WLAN as the phone.
2. On the camera, press **Menu → DLNA Access Control** and choose **All
   devices**. If you prefer the restricted setting, the camera will ask you to
   allow each new phone.
3. On the phone: join that same Wi-Fi, then open CamLink and tap **Find camera**.
4. **Watch the camera screen**: if it asks whether to allow the phone, choose
   **Allow**.

### Both modes

- If CamLink does not find the camera, tap **Enter camera IP** and type the
  gateway shown in the phone's Wi-Fi details (MobileLink: usually
  `192.168.101.1`).
- If it still does not find the camera, tap **Diagnose connection**. It reports
  the phone's Wi-Fi address and gateway, SSDP replies, the advertised
  description URL with its HTTP status/body/parse result, the Samsung
  MobileLink handshake responses, open TCP ports on the local network, and what
  the camera's web pages return. Use **Share** or **Copy** to send that report
  on.
- The card appears. Thumbnails load; all files are selected by default.
- Tap a photo to deselect it, or deselect everything and tap the ones you want.
  The selection ring and **N selected** counter update as you tap.
- Tap **Download N**. The result bar reports saved / skipped / failed counts,
  and the status card shows the live transfer speed.

CamLink pins all camera traffic to the camera's Wi-Fi network, so Android
sending internet traffic over mobile data does not break discovery.

Downloads are the only network operations. The camera is never modified.

## Build

Use Android Studio, or from a terminal with JDK 17+ (Android Studio's JBR
works):

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

Toolchain: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, compile/target SDK 34,
min SDK 29. `local.properties` must point at your SDK (`sdk.dir=...`).

Tests cover raw HTTP response handling, UPnP description parsing, SOAP/DIDL
parsing, URL resolution, file naming and manual-address candidates.

## Verified

- 22 JVM unit tests pass and the 1.5 debug APK builds.
- 1.5 re-checked in the Pixel 9 emulator against a mock DLNA camera: tapping a
  thumbnail deselects it (dimmed, empty ring, **N selected** falls) and the
  remaining selection downloads into `Pictures/CamLink`.
- 1.5 starts downloads with two connections and adds one while throughput is
  still rising (up to six), stopping the ramp on the first failure. The status
  card shows the live transfer speed.
- End-to-end against a mock DLNA camera in the Pixel 9 emulator: SSDP/manual
  connection, recursive Browse (root + `DCIM`), thumbnails, select all, download
  of 5 PNGs + 1 MP4 into the correct MediaStore folders.
- Downloading the same six files again reports `Saved 0 · skipped 6 · failed 0`.
- On the physical WB1100F, Wi-Fi, SSDP and the DLNA MediaServer/
  ContentDirectory advertisements are confirmed, and a full Browse/download run
  works in **Samsung Link → Nearby devices** mode.
- In **MobileLink** mode the camera answers `/description.xml` with **HTTP
  401** even with DLNA access control set, so since 1.4 CamLink sends the
  Samsung app's read-only pairing request before retrying. Its diagnostic
  section prints the result of every handshake request.

## Troubleshooting

- **Camera not found**: make sure the phone is still on the camera's Wi-Fi (not
  mobile data) and the camera is in **MobileLink** or **Samsung Link → Nearby
  devices** mode. In Samsung Link mode, **DLNA Access Control** must allow all
  devices. Try the manual IP. If it still fails, run **Diagnose connection** and
  share the report — it shows the phone's IP/gateway, SSDP answers, the
  description response, the MobileLink handshake and camera page contents.
- **Camera refused access (HTTP 401)**: the camera is up but has not approved
  this phone. CamLink sends the Samsung pairing request; look at the camera
  screen and choose **Allow / OK** when it asks. In Samsung Link mode also open
  Wi-Fi → Samsung Link (Nearby devices), press Menu → DLNA Access Control → All
  devices.
- **Found but no ContentDirectory**: the camera answered UPnP but did not
  advertise the media service. Send the connection details.
- **Thumbnails missing but downloads work**: normal on some cameras; the app
  falls back to a placeholder.
- **A file is skipped**: a file with that name already exists in
  `Pictures/CamLink` or `Movies/CamLink`.
- **Wi-Fi drops when the screen locks**: some cameras stop their access point
  when no client is active; keep the screen on during a large transfer.
- **Downloads stay slow**: CamLink raises its connection count only while the
  camera answers faster; if the speed stops climbing, the camera itself is the
  limit. Move the phone closer to the camera, avoid other traffic on the same
  Wi-Fi, and keep the screen on.

## Layout

```
app/src/main/java/com/camlab/camlink/
  net/DlnaXml.kt      pure XML/SOAP/DIDL parsing (JVM-testable)
  net/DlnaClient.kt   SSDP discovery, manual probing, Browse pagination
  net/RawHttp.kt      raw HTTP/1.0 GET for the camera's old web server
  net/SamsungLink.kt  MobileLink pairing headers/paths (read-only)
  net/Diagnostics.kt  read-only network report (SSDP, description, ports, HTTP)
  net/WifiLink.kt     finds the camera Wi-Fi and pins sockets to it
  net/Downloader.kt   MediaStore writes, skip-existing, adaptive parallel fetch, cancel
  net/Naming.kt       file-name sanitising and extension rules
  net/Models.kt       camera/media models
  ui/MainViewModel.kt discovery + browse + download state machine
  ui/MainActivity.kt  screens, dialogs, state rendering
  ui/PhotoAdapter.kt  thumbnail grid with selection
```
