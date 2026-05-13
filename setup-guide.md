# 🚀 Grafonia Screen Mirror — Complete Setup Guide

This guide walks you through setting up Grafonia from scratch, even if you've never done anything like this before.

---

## Table of Contents

1. [What You Need](#what-you-need)
2. [Running the Server Locally](#running-the-server-locally)
3. [Opening the Browser Viewer](#opening-the-browser-viewer)
4. [Building the Android App](#building-the-android-app)
5. [Connecting Your Phone](#connecting-your-phone)
6. [Deploying Online to Render](#deploying-online-to-render)
7. [Using Online (Internet Streaming)](#using-online-internet-streaming)
8. [Troubleshooting](#troubleshooting)
9. [Tips & Tricks](#tips--tricks)

---

## What You Need

| Requirement         | Details                                          |
|---------------------|--------------------------------------------------|
| Computer            | Windows, Mac, or Linux                           |
| Node.js             | Version 16 or higher — https://nodejs.org        |
| Android Phone       | Android 8.0 (Oreo) or higher                     |
| Android Studio      | For building the app — https://developer.android.com/studio |
| WiFi                | Phone and computer on the same network (local)   |

---

## Running the Server Locally

### Step 1: Install Node.js

Go to https://nodejs.org and download the **LTS** version. Install it normally.

Verify it works by opening a terminal and running:
```
node --version
```
You should see something like `v20.11.0`.

### Step 2: Start the Server

Open a terminal and navigate to the `server` folder:

```bash
cd grafonia/server
```

Install dependencies:
```bash
npm install
```

Start the server:
```bash
npm start
```

You'll see output like:
```
╔══════════════════════════════════════════╗
║       GRAFONIA SCREEN MIRROR SERVER      ║
╠══════════════════════════════════════════╣
║  Local:   http://localhost:3000          ║
║  Network: http://192.168.1.5:3000        ║
║  WS URL:  ws://192.168.1.5:3000          ║
╚══════════════════════════════════════════╝
```

Write down the **Network** IP address — you'll need it for the Android app.

> **Keep this terminal open while streaming.**

---

## Opening the Browser Viewer

Open a web browser (Chrome recommended) and go to:

```
http://localhost:3000
```

Or from another device on the same WiFi:
```
http://192.168.1.5:3000    ← use YOUR IP from the server output
```

You'll see the Grafonia interface with a "Waiting for Device" screen. That's correct — it's ready and waiting for your phone to connect.

---

## Building the Android App

### Step 1: Install Android Studio

Download from: https://developer.android.com/studio

Install it and run it at least once so it downloads the Android SDK.

### Step 2: Open the Project

1. Open Android Studio
2. Click **Open** (not "New Project")
3. Navigate to `grafonia/android-app`
4. Click **OK**
5. Wait for Gradle to sync (this can take a few minutes the first time)

### Step 3: Enable Developer Mode on Your Phone

On your Android phone:
1. Go to **Settings → About Phone**
2. Tap **Build Number** 7 times
3. Go back — you'll see **Developer Options**
4. Enable **Developer Options**
5. Enable **USB Debugging**

### Step 4: Connect Your Phone

Use a USB cable to connect your phone to your computer.

When prompted on your phone, tap **Allow** to allow USB debugging.

In Android Studio, your device should appear in the toolbar dropdown at the top.

### Step 5: Run the App

Click the green **▶ Run** button in Android Studio.

The app will install and open on your phone automatically.

> **Alternative: Build an APK**
> Go to `Build → Build Bundle(s) / APK(s) → Build APK(s)`
> Find the APK at `android-app/app/build/outputs/apk/debug/app-debug.apk`
> Transfer it to your phone and install it (you may need to enable "Install from unknown sources")

---

## Connecting Your Phone

1. Open the **Grafonia Mirror** app on your phone
2. In the **Server URL** field, enter:
   ```
   ws://192.168.1.5:3000
   ```
   (Replace with YOUR server IP from the terminal output)

3. **OR** scan the QR code:
   - In the browser viewer, click the QR icon (top right)
   - In the app, tap **📷 SCAN QR CODE**
   - Point your camera at the browser QR code

4. Tap **▶ START STREAMING**

5. A system dialog will appear: **"Grafonia wants to capture your screen"**
   → Tap **Start Now**

6. Your phone screen should appear in the browser within 1–2 seconds!

---

## Deploying Online to Render

Render offers free hosting for Node.js apps. This lets you stream over the internet (not just WiFi).

### Step 1: Push to GitHub

1. Create a free account at https://github.com
2. Create a new repository
3. Push the entire `grafonia/` folder to it:
   ```bash
   cd grafonia
   git init
   git add .
   git commit -m "Initial commit"
   git remote add origin https://github.com/YOUR_USERNAME/grafonia.git
   git push -u origin main
   ```

### Step 2: Deploy on Render

1. Go to https://render.com and sign up (free)
2. Click **New → Web Service**
3. Connect your GitHub account and select your repository
4. Fill in the settings:

   | Field        | Value          |
   |--------------|----------------|
   | Name         | grafonia-mirror |
   | Root Dir     | `server`       |
   | Environment  | Node           |
   | Build Cmd    | `npm install`  |
   | Start Cmd    | `npm start`    |
   | Plan         | Free           |

5. Click **Create Web Service**
6. Wait 2–3 minutes for deployment
7. You'll get a URL like: `https://grafonia-mirror.onrender.com`

### Step 3: Update Android App for Online Use

In the Android app, use your Render URL with `wss://` (secure WebSocket):

```
wss://grafonia-mirror.onrender.com
```

> **Note:** Free Render services spin down after 15 minutes of inactivity. The first connection may take 30–60 seconds to wake up.

---

## Using Online (Internet Streaming)

Once deployed on Render:

1. Open `https://grafonia-mirror.onrender.com` in any browser, anywhere in the world
2. In your Android app, enter `wss://grafonia-mirror.onrender.com`
3. Tap **Start Streaming**
4. Anyone with the browser link can watch your screen live!

> **Security Note:** The free version has no password. Don't stream sensitive information over the internet. For private use, only share the URL with people you trust.

---

## Troubleshooting

### "Connection Refused" in the Android app
- Make sure the server is running (`npm start`)
- Check the IP address matches the one in the terminal output
- Make sure your phone and computer are on the **same WiFi network**
- Try disabling your computer's firewall temporarily (Windows Firewall, etc.)
- If using Windows, allow Node.js through Windows Firewall when prompted

### "WebSocket: Failed to Connect"
- Double check the URL format: must start with `ws://` (local) or `wss://` (online)
- No trailing slashes: `ws://192.168.1.5:3000` ✅ not `ws://192.168.1.5:3000/` ❌

### App crashes immediately
- Make sure you're running Android 8.0+
- Grant the "Display over other apps" permission if requested

### Screen appears black or frozen
- Stop and restart streaming
- Reduce quality to 40% and FPS to 10 and try again
- Some secure apps (banking, Netflix) block screen capture by design

### Laggy / slow stream
- Lower quality (30–50%)
- Lower FPS (10–15)
- Use 5GHz WiFi instead of 2.4GHz
- Move closer to your router

### Render deployment fails
- Check that `Root Directory` is set to `server`
- Make sure `package.json` is inside the `server/` folder

---

## Tips & Tricks

| Tip | How |
|-----|-----|
| **Best quality** | Quality 80%, FPS 20, on 5GHz WiFi |
| **Lowest bandwidth** | Quality 30%, FPS 10 |
| **Multiple viewers** | Share the browser URL — unlimited viewers supported |
| **Full screen** | Click the fullscreen button in the browser, or press F for keyboard shortcut |
| **Screenshot** | Click the camera icon while streaming to save a PNG |
| **Auto-reconnect** | Both app and browser reconnect automatically if WiFi drops |
| **QR shortcut** | Use QR code scanning to skip typing the URL |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      Android Phone                          │
│                                                             │
│  ┌──────────────┐    ┌────────────────┐   ┌──────────────┐ │
│  │  MainActivity │───►│ScreenCaptureSvc│──►│StreamManager │ │
│  │  (UI/Perms)  │    │ (ForegroundSvc)│   │(ImageReader) │ │
│  └──────────────┘    └────────────────┘   └──────┬───────┘ │
│                                                   │ JPEG    │
│                                          ┌────────▼───────┐ │
│                                          │WebSocketManager│ │
│                                          │  (OkHttp WS)  │ │
└──────────────────────────────────────────┴────────┬───────┘─┘
                                                    │ Binary WS
                                          ┌─────────▼──────────┐
                                          │   Node.js Server   │
                                          │  server.js         │
                                          │  ┌───────────────┐ │
                                          │  │  WebSocket    │ │
                                          │  │  Relay        │ │
                                          │  │ sender → all  │ │
                                          │  │   viewers     │ │
                                          │  └───────┬───────┘ │
                                          └──────────┼─────────┘
                                          ┌──────────▼──────────┐
                               ┌──────────┤ Browser (WebSocket) │
                               │          └─────────────────────┘
                               │          (can have many viewers)
                        ┌──────▼──────┐
                        │  Canvas     │
                        │  Rendering  │
                        │  (JPEG→img) │
                        └─────────────┘
```

Data flow:
1. Android screen captured at set FPS
2. Each frame compressed to JPEG
3. JPEG bytes sent as binary WebSocket message to server
4. Server immediately broadcasts to all connected browser viewers
5. Browser decodes JPEG and draws on HTML5 Canvas
