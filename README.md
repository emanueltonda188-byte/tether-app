# Tether — our own NetShare replacement

Share your phone's mobile data with your PC. No root. No app on the PC.
No ads, no paid unlock, no account.

**Working title:** Tether (rename later).

## What v1 does

- Your phone creates a Wi-Fi Direct network (like a hotspot). Your PC
  joins it like any Wi-Fi network.
- An embedded HTTP proxy runs on the phone (default port **8080**). You
  type the phone's address once into Windows 11 proxy settings, and the
  PC's internet flows through the phone's mobile data.
- Big **Start / Stop** button, live status (network name, devices
  connected, data used this session), and the exact proxy address to type
  on the PC, shown large on screen.
- A **watchdog**: every 15 seconds it checks that the Wi-Fi Direct group
  and the proxy are still alive. If either died, it rebuilds it
  automatically and writes the event to the on-screen log. This is the
  fix for the NetShare complaint — NetShare just dies when the connection
  drops; Tether notices and restarts.
- First-launch **ColorOS setup checklist**: the three switches Oppo
  phones need so the system stops killing the app mid-session.
- Settings: proxy port (default 8080), optional auto-start on boot.

## What v1 does NOT do (honest)

- **Some apps on the PC won't work through it.** Anything that ignores
  the system proxy setting (some games, some chat apps, some launchers)
  cannot see the internet through a proxy. Normal browsing, YouTube,
  downloads and most apps work fine. This is the v1 compromise — it is
  the same limit NetShare and TetherFi have.
- **The true "just join the hotspot, zero setup" version is v2.** That
  needs Shizuku-level access to Android's hidden tethering system (the
  Shizzi approach). v1 is the proxy version, built first because it works
  today.
- **Stability is unproven on a real phone.** The code is written and
  structured, but Wi-Fi Direct behaviour on ColorOS can only be verified
  on the actual Oppo. See "How to test stability" below.

## How to get the APK (no Android Studio needed)

The developer laptop is a weak 4GB machine that cannot run Android
Studio well, so the APK is built in the cloud by GitHub Actions:

1. Create a repo on GitHub (your account — the assistant does **not**
   create repos or push code; that step is yours).
2. Upload / push this whole folder to the repo (main branch).
3. Open the repo → **Actions** tab → the latest "Build debug APK" run.
4. When it finishes green, download the **tether-debug-apk** artifact
   (it contains `app-debug.apk`).
5. If the run goes red, open the failed step's log — it will name the
   exact compile error, and that is what gets fixed next.

## Install on the ColorOS phone

1. Send `app-debug.apk` to the phone (USB, WhatsApp to yourself, or
   Google Drive — whatever is easiest).
2. Tap it to install. Allow **Install unknown apps** when the phone asks.
3. Open Tether. The **ColorOS setup checklist** appears first. Do all
   three, in order:
   1. **Battery → Unrestricted** — Settings → app page → Battery usage →
      Unrestricted (NOT Optimised).
   2. **Auto-launch ON** — same app page → Auto-launch → ON.
   3. **Lock Tether in Recents** — Recents screen → find Tether → pull
      the card down → tap the lock icon.
4. Tap **Done — start sharing**.

Skip these and ColorOS will kill the app mid-session. That is not a bug
in the app — it is the phone doing its job unless you tell it not to.

## Set up the PC (Windows 11, once only)

1. On the phone, tap **START SHARING**. The screen shows the network
   name, its Wi-Fi password, and the proxy address (something like
   `192.168.49.1:8080`).
2. On the PC, join that Wi-Fi network with the password shown.
3. Windows 11: **Settings → Network & internet → Proxy → Manual proxy
   setup → Use a proxy server → ON**.
4. Type the address (the IP before the colon) and the port (the number
   after the colon, usually 8080). Save.
5. Open a browser on the PC — it should load. If a page fails, check the
   phone's watchdog log first.

To stop: tap **STOP SHARING** on the phone, and turn the PC's manual
proxy back OFF (otherwise the PC keeps looking for a proxy that is gone).

## How to test stability

This is the real test — no numbers are claimed until it runs on the
phone:

1. Start sharing, connect the PC, and use it normally for an hour
   (YouTube, browsing, a download).
2. Watch the **Watchdog log** on the phone: every restart is timestamped.
   Zero restarts in an hour of normal use = good. Restarts with a reason
   ("group dropped", "proxy died") = the watchdog is doing its job, but
   note *how often* — that is the number that decides whether v1 is good
   enough or v2 needs to come sooner.
3. Also check: does the phone's mobile data stay on the whole time?
   (NetShare's bug was turning data off.) If data drops when the group
   starts, note the exact sequence — that is the most important finding.
4. Lock the phone screen for 10 minutes mid-session and check whether
   sharing survives — ColorOS is most aggressive when the screen is off.

## Project layout

app/src/main/java/com/tonda/tether/
  MainActivity.kt        Start/Stop, live status, proxy address, settings
  TetherService.kt       Foreground service + watchdog (the core)
  ProxyServer.kt         Embedded HTTP proxy (plain sockets, no libraries)
  WifiDirectManager.kt   Wi-Fi Direct group create/remove/monitor
  ColorOsSetupActivity.kt  First-launch ColorOS checklist
  BootReceiver.kt        Optional auto-start on boot
  TetherPrefs.kt         Settings storage
.github/workflows/build-apk.yml   Builds the debug APK in the cloud

## TODO (needs the real phone)

- Wi-Fi Direct group behaviour on ColorOS: does the group start while
  mobile data is on, or does data drop (the NetShare bug)?
- Is the group-owner IP reliably `192.168.49.1`?
- Does the group survive the screen locking?
- Proxy throughput under load (dozens of parallel browser connections).
- Whether ColorOS needs location switched ON for Wi-Fi Direct even with
  the permission granted.
