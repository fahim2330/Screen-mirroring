# 📱 Grafonia Screen Mirror

Real-time mobile screen mirroring system. Stream your Android phone screen to any browser over WiFi or the internet — completely free.

```
Android Phone ──── WebSocket ────► Node.js Server ──── WebSocket ────► Browser Viewer
   (sender)                          (relay)                            (viewer)
```

## Tech Stack

| Layer    | Tech                              |
|----------|-----------------------------------|
| Android  | Kotlin · MediaProjection · OkHttp |
| Server   | Node.js · Express · ws            |
| Frontend | HTML · CSS · Vanilla JS           |
| Hosting  | Render (free tier)                |

## Quick Start (Local)

### 1. Start the Server

```bash
cd server
npm install
npm start
```

Server starts at `http://localhost:3000`

### 2. Open the Viewer

Open `http://localhost:3000` in your browser (or `http://YOUR_IP:3000` from another device).

### 3. Build the Android App

1. Open `android-app/` in Android Studio
2. Edit `MainActivity.kt` if needed
3. Click **Run** or build APK: `Build → Build APK`
4. Install on your phone

### 4. Connect Your Phone

1. Open **Grafonia Mirror** app
2. Enter your PC's IP: `ws://192.168.x.x:3000`
3. Tap **Start Streaming**
4. Allow screen capture when prompted

Your screen appears in the browser instantly!

---

## Project Structure

```
grafonia/
├── android-app/                    # Android Studio project
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       └── java/com/grafonia/screenmirror/
│           ├── ui/MainActivity.kt          # Main UI + permission handling
│           ├── service/ScreenCaptureService.kt  # Foreground service
│           ├── websocket/WebSocketManager.kt    # WS client + auto-reconnect
│           └── stream/StreamManager.kt          # Screen capture + JPEG encode
│
├── server/                         # Node.js backend
│   ├── server.js                   # Express + WebSocket server
│   ├── package.json
│   ├── render.yaml                 # Render deployment config
│   └── .env.example
│
├── frontend/                       # Browser viewer
│   ├── index.html
│   ├── style.css
│   └── app.js
│
├── README.md
└── setup-guide.md                  # Detailed beginner guide
```

---

## Deploy to Render (Free Online Hosting)

See [setup-guide.md](setup-guide.md) for full instructions.

Short version:
1. Push to GitHub
2. Connect repo to [render.com](https://render.com)
3. Point root dir to `server/`
4. Deploy — get a free `*.onrender.com` URL
5. Use `wss://your-app.onrender.com` in the Android app

---

## Features

- ✅ Real-time screen mirroring
- ✅ JPEG frame streaming via WebSocket
- ✅ Multiple simultaneous viewers
- ✅ Auto-reconnect (both Android and browser)
- ✅ QR code for easy connection
- ✅ Adjustable quality (10–100%)
- ✅ Adjustable FPS (1–30)
- ✅ Live FPS / viewer count / latency stats
- ✅ Fullscreen mode
- ✅ Screenshot capture
- ✅ Runs in Android background (foreground service)
- ✅ Works on WiFi and internet

---

## License

MIT
