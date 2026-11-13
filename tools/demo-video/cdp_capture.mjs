#!/usr/bin/env node
/**
 * 마중 시연 영상 프레임 캡처 — 의존성 없는 Chrome DevTools Protocol 클라이언트 (Node 22+ 내장 WebSocket 사용)
 *
 *   node tools/demo-video/cdp_capture.mjs --url http://127.0.0.1:8787/composition.html \
 *        --out /tmp/frames --fps 30 --duration 80 --width 1920 --height 1080 [--serve <dir>] [--port 9333]
 *
 * 합성 페이지는 window.__ready 와 window.__seek(ms) → Promise 를 노출해야 한다.
 * (결정적 렌더링: CSS 애니메이션 대신 t 기반 상태 계산 + <video>.currentTime 시킹)
 */
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { serve } from "./static_server.mjs";

const args = Object.fromEntries(process.argv.slice(2).map((a, i, arr) => a.startsWith("--") ? [a.slice(2), arr[i + 1]] : []).filter(Boolean));
const url = args.url;
const out = args.out || "frames";
const fps = Number(args.fps || 30);
const duration = Number(args.duration || 10);
const width = Number(args.width || 1920);
const height = Number(args.height || 1080);
const chrome = args.chrome || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const port = Number(args.port || 9333);
const log = (...m) => console.error(`[cdp ${((Date.now() - T0) / 1000).toFixed(1)}s]`, ...m);
const T0 = Date.now();
if (!url) { console.error("--url required"); process.exit(1); }
fs.mkdirSync(out, { recursive: true });
let staticServer = null;
if (args.serve) { staticServer = await serve(args.serve, Number(args.servePort || 8787)); log("static server on", args.servePort || 8787); }

const profile = fs.mkdtempSync("/tmp/majung-chrome-");
const proc = spawn(chrome, [
  `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, "--headless=new", "--disable-gpu",
  "--hide-scrollbars", "--mute-audio", "--autoplay-policy=no-user-gesture-required", "--no-first-run", "--no-default-browser-check",
  `--window-size=${width},${height}`, "--force-device-scale-factor=1", "about:blank"
], { stdio: "ignore" });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function getTargets() {
  for (let i = 0; i < 100; i++) {
    try { const r = await fetch(`http://127.0.0.1:${port}/json`, { signal: AbortSignal.timeout(2000) }); return await r.json(); } catch { await sleep(200); }
  }
  throw new Error("Chrome DevTools endpoint did not come up");
}

const targets = await getTargets();
const page = targets.find((t) => t.type === "page");
log("target", page.id);
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let id = 0; const pending = new Map();
ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
const send = (method, params = {}, timeoutMs = 30000) => new Promise((resolve, reject) => {
  const mid = ++id;
  const timer = setTimeout(() => { pending.delete(mid); reject(new Error(`${method} timed out`)); }, timeoutMs);
  pending.set(mid, (m) => { clearTimeout(timer); m.error ? reject(new Error(method + ": " + JSON.stringify(m.error))) : resolve(m.result); });
  ws.send(JSON.stringify({ id: mid, method, params }));
});

await send("Page.enable");
await send("Runtime.enable");
await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: false });
await send("Page.navigate", { url });
log("navigated");
let ready = false;
for (let i = 0; i < 300; i++) {
  const r = await send("Runtime.evaluate", { expression: "window.__ready === true", returnByValue: true });
  if (r.result.value === true) { ready = true; break; }
  await sleep(100);
}
log("ready:", ready);
if (!ready) {
  const diag = await send("Runtime.evaluate", { expression: "JSON.stringify({ready: window.__ready, err: window.__initError || null, video: document.getElementById('rec') && document.getElementById('rec').readyState})", returnByValue: true });
  log("diagnostic:", diag.result.value);
}

const total = Math.round(fps * duration);
for (let f = 0; f < total; f++) {
  const ms = Math.round((f / fps) * 1000);
  const r = await send("Runtime.evaluate", { expression: `window.__seek(${ms})`, awaitPromise: true, returnByValue: true, timeout: 15000 }, 20000);
  if (r.exceptionDetails) log("seek exception @", ms, JSON.stringify(r.exceptionDetails).slice(0, 300));
  const shot = await send("Page.captureScreenshot", { format: "jpeg", quality: 92, captureBeyondViewport: false });
  fs.writeFileSync(path.join(out, `f${String(f).padStart(5, "0")}.jpg`), Buffer.from(shot.data, "base64"));
  if (f % (fps * 5) === 0) log(`${f}/${total} frames`);
}
log(`done: ${total} frames → ${out}`);
ws.close(); proc.kill("SIGTERM");
if (staticServer) staticServer.close();
try { fs.rmSync(profile, { recursive: true, force: true }); } catch {}
process.exit(0);
