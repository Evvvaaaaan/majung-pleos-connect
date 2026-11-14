#!/usr/bin/env node
/**
 * 시연 녹화 중 중계 서버의 STATE_UPDATE / SESSION_SHREDDED 이벤트를 타임스탬프와 함께 JSON Lines로 기록한다.
 * 영상 합성 시 동승자 웹을 같은 시각에 같은 상태로 재생하기 위한 타임라인 소스.
 *
 *   node tools/demo-video/sse_log.mjs --base http://localhost:8080 --out timeline.jsonl
 * 세션이 생성될 때까지 /healthz를 폴링하고, 첫 세션의 이벤트 스트림을 구독한다.
 */
import fs from "node:fs";

const args = Object.fromEntries(process.argv.slice(2).map((a, i, arr) => a.startsWith("--") ? [a.slice(2), arr[i + 1]] : []).filter(Boolean));
const base = args.base || "http://localhost:8080";
const out = args.out || "timeline.jsonl";
const t0 = Number(args.t0 || Date.now());
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 세션 id는 외부에 노출되지 않으므로 루프백 전용 /api/v1/sessions/_latest 로 조회한다.
// 시작 시점에 이미 있던 세션(이전 시연)은 건너뛰고, 새로 만들어지는 세션을 기다린다.
async function latest() {
  try { const r = await fetch(`${base}/api/v1/sessions/_latest`); if (r.ok) { const j = await r.json(); passengerToken = j.passengerToken || passengerToken; return j.sessionId; } } catch {}
  return null;
}
let passengerToken = args.token || "";
let sessionId = args.session || null;
const stale = sessionId ? null : await latest();
while (!sessionId) {
  const id = await latest();
  if (id && id !== stale) sessionId = id;
  else await sleep(200);
}
console.error(`subscribing to ${sessionId}`);
const stream = fs.createWriteStream(out, { flags: "a" });
const res = await fetch(`${base}/api/v1/sessions/${encodeURIComponent(sessionId)}/events?t=${encodeURIComponent(passengerToken)}`);
const reader = res.body.getReader();
const dec = new TextDecoder();
let buf = "";
while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  buf += dec.decode(value, { stream: true });
  const frames = buf.split("\n\n"); buf = frames.pop() || "";
  for (const frame of frames) {
    const ev = frame.split("\n").find((l) => l.startsWith("event: "))?.slice(7).trim();
    const data = frame.split("\n").find((l) => l.startsWith("data: "))?.slice(6);
    if (!ev || !data) continue;
    const rec = { t: Date.now() - t0, event: ev, data: JSON.parse(data) };
    stream.write(JSON.stringify(rec) + "\n");
    console.error(`${(rec.t / 1000).toFixed(2)}s ${ev} ${rec.data.phase || ""} stopped=${rec.data.stoppedInPark} door=${rec.data.passengerDoorOpen} seated=${rec.data.passengerSeated} belt=${rec.data.passengerBuckled}`);
    if (ev === "SESSION_SHREDDED") { stream.end(); process.exit(0); }
  }
}
