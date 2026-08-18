const pairScreen = document.getElementById("pair-screen");
const remoteScreen = document.getElementById("remote-screen");
const pairForm = document.getElementById("pair-form");
const codeInput = document.getElementById("code-input");
const pairStatus = document.getElementById("pair-status");
const connStatus = document.getElementById("conn-status");
const canvas = document.getElementById("screen-canvas");
const ctx = canvas.getContext("2d");
const textForm = document.getElementById("text-form");
const textInput = document.getElementById("text-input");

let ws = null;
let deviceWidth = 0;
let deviceHeight = 0;

// Must match RelayConnection.kt's TYPE_VIDEO / TYPE_AUDIO prefix bytes.
const FRAME_TYPE_VIDEO = 1;
const FRAME_TYPE_AUDIO = 2;
const AUDIO_SAMPLE_RATE = 16000;

let audioCtx = null;
let nextPlayTime = 0;

// Browsers block audio playback until the page has seen a real user gesture.
// Auto-reconnect happens with no click at all, so retry resume() on the
// first interaction the user makes anywhere on the page.
document.addEventListener(
  "pointerdown",
  () => {
    if (audioCtx && audioCtx.state === "suspended") audioCtx.resume();
  },
  { once: true }
);

function playAudioChunk(buffer) {
  if (!audioCtx) {
    audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: AUDIO_SAMPLE_RATE });
    nextPlayTime = audioCtx.currentTime;
  }
  if (audioCtx.state === "suspended") audioCtx.resume();

  const int16 = new Int16Array(buffer);
  const float32 = new Float32Array(int16.length);
  for (let i = 0; i < int16.length; i++) float32[i] = int16[i] / 32768;

  const audioBuffer = audioCtx.createBuffer(1, float32.length, AUDIO_SAMPLE_RATE);
  audioBuffer.copyToChannel(float32, 0);

  const source = audioCtx.createBufferSource();
  source.buffer = audioBuffer;
  source.connect(audioCtx.destination);

  // Schedule back-to-back so chunks play in a continuous stream instead of
  // stacking on top of each other or leaving silent gaps between them.
  const startAt = Math.max(nextPlayTime, audioCtx.currentTime);
  source.start(startAt);
  nextPlayTime = startAt + audioBuffer.duration;
}

function wsUrl() {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  return `${proto}//${location.host}/ws`;
}

pairForm.addEventListener("submit", (e) => {
  e.preventDefault();
  const code = codeInput.value.trim();
  if (!/^\d{6}$/.test(code)) {
    pairStatus.textContent = "6자리 숫자를 입력하세요.";
    return;
  }
  connect(code);
});

const LAST_CODE_KEY = "hp_remote_code";
let currentCode = null;

function connect(code) {
  currentCode = code;
  pairStatus.textContent = "연결 중...";
  ws = new WebSocket(wsUrl());
  ws.binaryType = "arraybuffer";

  ws.addEventListener("open", () => {
    ws.send(JSON.stringify({ type: "register", role: "controller", code }));
  });

  let videoFrameCount = 0;
  let audioFrameCount = 0;

  ws.addEventListener("message", (ev) => {
    if (ev.data instanceof ArrayBuffer) {
      const type = new Uint8Array(ev.data, 0, 1)[0];
      const payload = ev.data.slice(1);
      if (type === FRAME_TYPE_VIDEO) {
        videoFrameCount++;
        if (videoFrameCount <= 3) console.log("[hp_remote] video frame", videoFrameCount, "bytes:", payload.byteLength);
        renderFrame(payload).catch((err) => console.error("[hp_remote] renderFrame failed:", err));
      } else if (type === FRAME_TYPE_AUDIO) {
        audioFrameCount++;
        if (audioFrameCount <= 3) console.log("[hp_remote] audio frame", audioFrameCount, "bytes:", payload.byteLength);
        playAudioChunk(payload);
      } else {
        console.warn("[hp_remote] unknown binary frame type:", type, "bytes:", ev.data.byteLength);
      }
      return;
    }
    try {
      const msg = JSON.parse(ev.data);
      handleMessage(msg);
    } catch (err) {
      console.error("[hp_remote] failed to handle message:", ev.data, err);
    }
  });

  ws.addEventListener("close", () => {
    connStatus.textContent = "연결 끊김";
    showPairScreen();
  });

  ws.addEventListener("error", () => {
    pairStatus.textContent = "연결 오류가 발생했습니다.";
  });
}

function handleMessage(msg) {
  switch (msg.type) {
    case "registered":
      deviceWidth = msg.width || 0;
      deviceHeight = msg.height || 0;
      if (deviceWidth && deviceHeight) setCanvasSize(deviceWidth, deviceHeight);
      showRemoteScreen();
      connStatus.textContent = "연결됨";
      if (currentCode) localStorage.setItem(LAST_CODE_KEY, currentCode);
      break;
    case "info":
      deviceWidth = msg.width;
      deviceHeight = msg.height;
      setCanvasSize(deviceWidth, deviceHeight);
      break;
    case "error":
      pairStatus.textContent = msg.message;
      break;
    case "device-left":
      connStatus.textContent = "기기 연결이 끊어졌습니다.";
      showPairScreen();
      break;
  }
}

function setCanvasSize(w, h) {
  canvas.width = w;
  canvas.height = h;
}

async function renderFrame(buffer) {
  const blob = new Blob([buffer], { type: "image/jpeg" });
  const bitmap = await createImageBitmap(blob);
  if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
    setCanvasSize(bitmap.width, bitmap.height);
  }
  ctx.drawImage(bitmap, 0, 0);
  bitmap.close();
}

function showRemoteScreen() {
  pairScreen.classList.add("hidden");
  remoteScreen.classList.remove("hidden");
}

function showPairScreen() {
  remoteScreen.classList.add("hidden");
  pairScreen.classList.remove("hidden");
  pairStatus.textContent = "";
}

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

// --- pointer input: translate canvas coordinates to device pixel coordinates ---

function toDeviceCoords(ev) {
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const scaleY = canvas.height / rect.height;
  return {
    x: Math.round((ev.clientX - rect.left) * scaleX),
    y: Math.round((ev.clientY - rect.top) * scaleY),
  };
}

let dragStart = null;

canvas.addEventListener("pointerdown", (ev) => {
  canvas.setPointerCapture(ev.pointerId);
  dragStart = { ...toDeviceCoords(ev), t: performance.now() };
});

canvas.addEventListener("pointerup", (ev) => {
  if (!dragStart) return;
  const end = { ...toDeviceCoords(ev), t: performance.now() };
  const dx = end.x - dragStart.x;
  const dy = end.y - dragStart.y;
  const dist = Math.hypot(dx, dy);
  const duration = Math.max(1, Math.round(end.t - dragStart.t));

  if (dist < 12) {
    send({ type: "tap", x: dragStart.x, y: dragStart.y });
  } else {
    send({ type: "swipe", x1: dragStart.x, y1: dragStart.y, x2: end.x, y2: end.y, duration });
  }
  dragStart = null;
});

canvas.addEventListener("pointercancel", () => {
  dragStart = null;
});

// --- toolbar buttons ---

document.getElementById("btn-back").addEventListener("click", () => send({ type: "key", action: "back" }));
document.getElementById("btn-home").addEventListener("click", () => send({ type: "key", action: "home" }));
document.getElementById("btn-recents").addEventListener("click", () => send({ type: "key", action: "recents" }));
document.getElementById("btn-disconnect").addEventListener("click", () => {
  if (ws) ws.close();
});

textForm.addEventListener("submit", (e) => {
  e.preventDefault();
  const text = textInput.value;
  if (!text) return;
  send({ type: "text", text });
  textInput.value = "";
});

// --- remember the last working code and reconnect automatically on load ---

const savedCode = localStorage.getItem(LAST_CODE_KEY);
if (savedCode && /^\d{6}$/.test(savedCode)) {
  codeInput.value = savedCode;
  connect(savedCode);
}
