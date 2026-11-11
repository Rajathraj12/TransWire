# TransWire

Cross-network and offline Android file transfer app built with Jetpack Compose, Wi-Fi Direct, WebRTC DataChannels, and a stateless Node.js Socket.IO signaling server.

## Key Capabilities

- **Nearby Offline Transfer (Wi-Fi Direct + TCP):** Transfers files directly over a local Wi-Fi Direct group socket with no internet or Bluetooth required.
- **Remote Internet Transfer (WebRTC P2P + TURN):** Pairs devices anywhere via QR code (camera scan or gallery image upload) or a 6-digit session PIN, then streams files directly over multi-channel WebRTC DataChannels (`ordered = true` control lane + `3` unordered data lanes).
- **Integrity & Resume:** Streams files in `56 KiB` chunks with `ChunkRangeTracker` + Room persistence, verifies full-file SHA-256 checksums before saving to `Downloads/Transwire`, and waits for receiver `CompletionAck` verification before completing.
- **Privacy by Design:** Signaling backend relays only pairing/SDP/ICE metadata in memory and never touches or stores file bytes. Android Auto Backup excludes local Room transfer history (`transwire_database`). Users can toggle history retention, clear history/cache, and run connection diagnostics in Settings.

## Repository Structure

```text
Transwire/
├── app/                         # Android Kotlin + Jetpack Compose app (minSdk 24, targetSdk 37)
│   ├── proguard-rules.pro       # R8 keep rules for WebRTC, Socket.IO, ML Kit, Room
│   └── build.gradle.kts         # App build & release signing config
├── backend/                     # Node.js + Express + Socket.IO signaling & TURN broker
│   ├── src/index.ts             # /health and /turn-credentials (Cloudflare TURN + fallback)
│   ├── src/socketHandlers.ts    # QR/PIN pairing, approval, rejoin, retry-transfer, SDP/ICE relay
│   └── src/sessionManager.ts    # Ephemeral in-memory session & rate-limit state
├── keystore.properties.example  # Template for release signing configuration
└── render.yaml                  # Render deployment manifest for backend
```

## Backend Setup (`backend/`)

1. Copy `backend/.env.example` to `backend/.env` (or configure Render Environment Variables):
   - Recommended: `CLOUDFLARE_TURN_KEY_ID` and `CLOUDFLARE_TURN_API_TOKEN` (short-lived Cloudflare Realtime TURN credentials across ports `3478`, `80`, `443`, `5349`).
   - Optional fallbacks: `TURN_SERVER_URLS` + `TURN_USERNAME` / `TURN_PASSWORD` (static relay) or `TURN_SHARED_SECRET` (Coturn HMAC).
2. Build & start:
   ```bash
   cd backend
   npm install
   npm run build
   npm start
   ```

## Android Release Signing & Build (`app/`)

1. Generate or place your release keystore (`.jks`) outside git tracking.
2. Copy `keystore.properties.example` to `keystore.properties` in the repository root and set:
   ```properties
   storeFile=path/to/transwire-release.jks
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=YOUR_KEY_ALIAS
   keyPassword=YOUR_KEY_PASSWORD
   ```
   *(Alternatively export `TRANSWIRE_STORE_FILE`, `TRANSWIRE_STORE_PASSWORD`, `TRANSWIRE_KEY_ALIAS`, and `TRANSWIRE_KEY_PASSWORD`.)*
3. Build from Android Studio (**Build → Generate Signed App Bundle / APK**) or CLI:
   ```bash
   ./gradlew assembleRelease bundleRelease
   ```
   Outputs:
   - Signed APK: `app/build/outputs/apk/release/app-release.apk`
   - Signed App Bundle: `app/build/outputs/bundle/release/app-release.aab`

## Operational Notes

- Free Render instances sleep after inactivity and may take 20–50 seconds on the first request.
- Symmetric NAT / firewall connections use TURN relay quota (1,000 GB/month free on Cloudflare); direct P2P over STUN and Nearby Wi-Fi Direct use 0 TURN bandwidth.
- Both sender and receiver should run matching app versions to use `CompletionAck`, `56 KiB` chunks, and multi-channel WebRTC striping.
