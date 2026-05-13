// ============================================================
// GRAFONIA SCREEN MIRROR — FRONTEND VIEWER
// Connects to server via WebSocket and displays live frames
// ============================================================

(function () {
  "use strict";

  // ──────────────────────────────────────────────
  // Config
  // ──────────────────────────────────────────────
  const WS_RECONNECT_DELAY = 3000;   // ms between reconnect attempts
  const PING_INTERVAL = 5000;        // ms between pings
  const MAX_RECONNECTS = 50;

  // ──────────────────────────────────────────────
  // DOM References
  // ──────────────────────────────────────────────
  const $ = (id) => document.getElementById(id);

  const el = {
    statusPill:      $("statusPill"),
    statusDot:       $("statusDot"),
    statusText:      $("statusText"),
    fpsValue:        $("fpsValue"),
    viewersValue:    $("viewersValue"),
    deviceName:      $("deviceName"),
    deviceMeta:      $("deviceMeta"),
    fpsLive:         $("fpsLive"),
    viewersLive:     $("viewersLive"),
    latencyLive:     $("latencyLive"),
    streamStatus:    $("streamStatus"),
    serverStatus:    $("serverStatus"),
    wsState:         $("wsState"),
    reconnectCount:  $("reconnectCount"),
    screenPlaceholder: $("screenPlaceholder"),
    screenCanvas:    $("screenCanvas"),
    placeholderTitle: $("placeholderTitle"),
    placeholderSub:  $("placeholderSub"),
    overlayDevice:   $("overlayDevice"),
    overlayFps:      $("overlayFps"),
    serverUrlValue:  $("serverUrlValue"),
    qrImage:         $("qrImage"),
    qrLoading:       $("qrLoading"),
    qrModalImage:    $("qrModalImage"),
    qrModal:         $("qrModal"),
    copyUrlBtn:      $("copyUrlBtn"),
    qrBtn:           $("qrBtn"),
    qrModalClose:    $("qrModalClose"),
    fullscreenBtn:   $("fullscreenBtn"),
    fullscreenBtn2:  $("fullscreenBtn2"),
    screenshotBtn:   $("screenshotBtn"),
  };

  // ──────────────────────────────────────────────
  // State
  // ──────────────────────────────────────────────
  let ws = null;
  let reconnectCount = 0;
  let reconnectTimer = null;
  let pingTimer = null;
  let pingStart = 0;
  let latencyMs = 0;
  let currentFps = 0;
  let deviceName = "Unknown Device";
  let senderOnline = false;
  let isFullscreen = false;

  // Canvas context
  const canvas = el.screenCanvas;
  const ctx = canvas.getContext("2d");

  // ──────────────────────────────────────────────
  // Derive WebSocket URL
  // ──────────────────────────────────────────────
  function getWsUrl() {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    return `${proto}://${location.host}`;
  }

  // ──────────────────────────────────────────────
  // WebSocket Connection
  // ──────────────────────────────────────────────
  function connect() {
    if (ws && ws.readyState <= 1) return; // Already connecting/open

    const url = getWsUrl();
    console.log(`[WS] Connecting to ${url}`);

    setStatus("connecting", "Connecting…");
    updateWsState("CONNECTING");

    ws = new WebSocket(url);

    ws.binaryType = "arraybuffer"; // Receive binary frames efficiently

    ws.addEventListener("open", onOpen);
    ws.addEventListener("message", onMessage);
    ws.addEventListener("close", onClose);
    ws.addEventListener("error", onError);
  }

  function onOpen() {
    console.log("[WS] Connection opened");
    reconnectCount = 0;
    el.reconnectCount.textContent = "0";
    setStatus("connected", "Connected to Server");
    updateWsState("OPEN");
    el.serverStatus.textContent = "ONLINE";

    // Register as a viewer
    send({ type: "register_viewer" });

    // Start pinging
    startPing();
  }

  function onMessage(event) {
    // ── Binary = screen frame ──
    if (event.data instanceof ArrayBuffer) {
      renderFrame(event.data);
      return;
    }

    // ── Text = JSON control message ──
    try {
      const msg = JSON.parse(event.data);
      handleMessage(msg);
    } catch (e) {
      console.warn("[WS] Failed to parse message:", e);
    }
  }

  function handleMessage(msg) {
    switch (msg.type) {
      case "sender_connected":
        senderOnline = true;
        deviceName = msg.deviceName || "Android Device";
        showScreenCanvas();
        el.deviceName.textContent = deviceName;
        el.deviceMeta.textContent = formatDeviceInfo(msg.deviceInfo);
        el.overlayDevice.textContent = deviceName;
        setStatus("streaming", `Streaming: ${deviceName}`);
        el.streamStatus.textContent = "LIVE";
        el.streamStatus.style.color = "var(--green)";
        break;

      case "sender_disconnected":
        senderOnline = false;
        showPlaceholder("Device Disconnected", "The Android device stopped streaming. Waiting for reconnection…");
        el.deviceName.textContent = "–";
        el.deviceMeta.textContent = "Device offline";
        setStatus("connected", "Connected — No Stream");
        el.streamStatus.textContent = "OFFLINE";
        el.streamStatus.style.color = "var(--red)";
        break;

      case "stats":
        currentFps = msg.fps || 0;
        el.fpsValue.textContent = currentFps;
        el.fpsLive.textContent = `${currentFps} fps`;
        el.viewersValue.textContent = msg.viewers || 1;
        el.viewersLive.textContent = msg.viewers || 1;
        el.overlayFps.textContent = `${currentFps} FPS`;
        break;

      case "pong":
        latencyMs = Date.now() - pingStart;
        el.latencyLive.textContent = `${latencyMs}ms`;
        break;

      default:
        break;
    }
  }

  function onClose(event) {
    console.log(`[WS] Closed: code=${event.code}`);
    stopPing();
    updateWsState("CLOSED");
    el.serverStatus.textContent = "OFFLINE";

    if (senderOnline) {
      senderOnline = false;
      showPlaceholder("Connection Lost", "Attempting to reconnect…");
    }

    setStatus("disconnected", "Disconnected");

    // Auto-reconnect
    if (reconnectCount < MAX_RECONNECTS) {
      reconnectCount++;
      el.reconnectCount.textContent = reconnectCount;
      console.log(`[WS] Reconnecting in ${WS_RECONNECT_DELAY}ms (attempt ${reconnectCount})`);

      reconnectTimer = setTimeout(() => {
        setStatus("connecting", `Reconnecting (${reconnectCount})…`);
        connect();
      }, WS_RECONNECT_DELAY);
    }
  }

  function onError(err) {
    console.error("[WS] Error:", err);
  }

  function send(data) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(data));
    }
  }

  // ──────────────────────────────────────────────
  // Ping/Latency
  // ──────────────────────────────────────────────
  function startPing() {
    pingTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        pingStart = Date.now();
        send({ type: "ping" });
      }
    }, PING_INTERVAL);
  }

  function stopPing() {
    clearInterval(pingTimer);
  }

  // ──────────────────────────────────────────────
  // Frame Rendering
  // ──────────────────────────────────────────────
  const img = new Image();

  function renderFrame(buffer) {
    const blob = new Blob([buffer], { type: "image/jpeg" });
    const url = URL.createObjectURL(blob);

    img.onload = () => {
      // Resize canvas to match incoming frame
      if (canvas.width !== img.width || canvas.height !== img.height) {
        canvas.width = img.width;
        canvas.height = img.height;
      }

      ctx.drawImage(img, 0, 0);
      URL.revokeObjectURL(url); // Free memory
    };

    img.src = url;
  }

  // ──────────────────────────────────────────────
  // UI State Helpers
  // ──────────────────────────────────────────────
  function setStatus(state, text) {
    el.statusText.textContent = text;
    el.statusDot.className = "status-dot";
    el.statusPill.className = "status-pill";

    if (state === "connected" || state === "streaming") {
      el.statusDot.classList.add("connected");
      el.statusPill.classList.add("connected");
    } else if (state === "disconnected") {
      el.statusDot.classList.add("disconnected");
      el.statusPill.classList.add("disconnected");
    } else if (state === "connecting") {
      el.statusDot.classList.add("connecting");
    }
  }

  function updateWsState(state) {
    el.wsState.textContent = state;
    el.wsState.style.color = state === "OPEN" ? "var(--green)" : state === "CLOSED" ? "var(--red)" : "var(--yellow)";
  }

  function showScreenCanvas() {
    el.screenPlaceholder.classList.add("hidden");
    el.screenCanvas.classList.remove("hidden");
  }

  function showPlaceholder(title, sub) {
    el.placeholderTitle.textContent = title;
    el.placeholderSub.textContent = sub;
    el.screenCanvas.classList.add("hidden");
    el.screenPlaceholder.classList.remove("hidden");
  }

  function formatDeviceInfo(info) {
    if (!info || Object.keys(info).length === 0) return "Android Device";
    const parts = [];
    if (info.model) parts.push(info.model);
    if (info.androidVersion) parts.push(`Android ${info.androidVersion}`);
    if (info.resolution) parts.push(info.resolution);
    return parts.join(" · ") || "Android Device";
  }

  // ──────────────────────────────────────────────
  // QR Code
  // ──────────────────────────────────────────────
  async function loadQr() {
    try {
      const res = await fetch("/api/qr");
      const data = await res.json();

      el.qrLoading.classList.add("hidden");
      el.qrImage.src = data.qr;
      el.qrImage.classList.remove("hidden");
      el.qrModalImage.src = data.qr;
      el.serverUrlValue.textContent = data.url;
    } catch (e) {
      el.qrLoading.textContent = "Failed to load QR";
    }
  }

  // ──────────────────────────────────────────────
  // Server URL display
  // ──────────────────────────────────────────────
  function showServerUrl() {
    el.serverUrlValue.textContent = getWsUrl();
  }

  // ──────────────────────────────────────────────
  // Event Listeners
  // ──────────────────────────────────────────────

  // Copy URL
  el.copyUrlBtn.addEventListener("click", () => {
    const url = el.serverUrlValue.textContent;
    if (url && url !== "–") {
      navigator.clipboard.writeText(url).then(() => {
        el.copyUrlBtn.textContent = "COPIED!";
        el.copyUrlBtn.classList.add("copied");
        setTimeout(() => {
          el.copyUrlBtn.textContent = "COPY";
          el.copyUrlBtn.classList.remove("copied");
        }, 2000);
      });
    }
  });

  // QR Modal
  el.qrBtn.addEventListener("click", () => {
    el.qrModal.classList.remove("hidden");
  });
  el.qrModalClose.addEventListener("click", () => {
    el.qrModal.classList.add("hidden");
  });
  el.qrModal.addEventListener("click", (e) => {
    if (e.target === el.qrModal) el.qrModal.classList.add("hidden");
  });

  // Fullscreen
  function toggleFullscreen() {
    isFullscreen = !isFullscreen;
    document.body.classList.toggle("fullscreen-mode", isFullscreen);
  }
  el.fullscreenBtn.addEventListener("click", toggleFullscreen);
  el.fullscreenBtn2.addEventListener("click", toggleFullscreen);

  // ESC to exit fullscreen
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && isFullscreen) toggleFullscreen();
  });

  // Screenshot
  el.screenshotBtn.addEventListener("click", () => {
    if (canvas.width === 0) return;

    const link = document.createElement("a");
    link.download = `grafonia-screenshot-${Date.now()}.png`;
    link.href = canvas.toDataURL("image/png");
    link.click();
  });

  // ──────────────────────────────────────────────
  // Init
  // ──────────────────────────────────────────────
  function init() {
    showServerUrl();
    loadQr();
    connect();

    // Show initial loading state
    setStatus("connecting", "Connecting…");
    el.serverStatus.textContent = "–";
  }

  // Wait for DOM then init
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
