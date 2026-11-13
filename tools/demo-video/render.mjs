#!/usr/bin/env node
/**
 * 마중 시연 영상 렌더 파이프라인 (의존성: node 22+, ffmpeg, Google Chrome)
 *
 *   node tools/demo-video/render.mjs --work <dir> \
 *     --pleos <recording.mp4> --pleosTimeline <timeline.jsonl> --pleosTaps "start=2259,back=5644,preset=10845,unlock=17272,wrong=23328,doorfr=30443,seated=34530,complete=42960" \
 *     --sim <recording.mp4>   --simTimeline <timeline.jsonl>   --simTaps "start=2071,drive=5149" \
 *     --narration <dir> --out docs/demo/majung_demo.mp4
 *
 * 장면: 타이틀 → 3가지 → [데모 A: Pleos Connect 에뮬레이터 실녹화(전체 화면 16:9)] → [데모 B: AAOS 시뮬레이션 주행 발췌(앱 영역 크롭)] → 구조·검증 → 엔딩
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "..", "..");
const args = Object.fromEntries(process.argv.slice(2).map((a, i, arr) => a.startsWith("--") ? [a.slice(2), arr[i + 1]] : []).filter(Boolean));
const work = path.resolve(args.work); fs.mkdirSync(work, { recursive: true });
const fps = Number(args.fps || 30);
const taps = (s) => Object.fromEntries((s || "").split(",").filter(Boolean).map((kv) => { const [k, v] = kv.split("="); return [k, Number(v)]; }));
const dur = (f) => Number(execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f]).toString().trim());
const readTl = (f) => fs.readFileSync(path.resolve(f), "utf-8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)).map((r) => ({ t: r.t, event: r.event, data: r.data }));

// ---- 1) 녹화 변환 ------------------------------------------------------------------------------
function transcode(src, out, vf) {
  execFileSync("ffmpeg", ["-v", "error", "-y", "-i", path.resolve(src), "-vf", `${vf},fps=${fps}`, "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an", out]);
  return dur(out);
}
const demos = [];
let cursor = 26000; // title 11s + three 15s
const S = { title: { start: 0, end: 11000 }, three: { start: 11000, end: 26000 } };

if (args.pleos) {
  const out = path.join(work, "recording_pleos.mp4");
  const d = transcode(args.pleos, out, "scale=1284:722");
  const tp = taps(args.pleosTaps);
  const len = Math.min(Math.round(d * 1000), Number(args.pleosMs || 50000));
  S.demoA = { start: cursor, end: cursor + len }; cursor += len;
  demos.push({
    scene: "demoA", video: "recording_pleos.mp4", real: true, offsetMs: Number(args.pleosOffset || -400), box: { w: 1284, h: 722, top: 150 },
    tag: "Pleos Connect v2.1.0 에뮬레이터 · pleos 빌드 [SDK 실연동] · Virtual Console 신호 주입", pill: "Pleos Connect 전용 에뮬레이터 실녹화",
    credit: "실제 Vehicle SDK 콜백·제어 (Pleos Connect Emulator v2.1.0 · API 34 · 2560×1440)",
    webStates: readTl(args.pleosTimeline),
    captions: [
      { t: tp.start, n: 1, title: "픽업 시작 · 링크 발급", sub: "NaviHelper.requestRoute → 내비 경로 설정 · 일회성 웹 링크" },
      { t: tp.back, n: 1, title: "내비 경로 설정 후 앱 복귀", sub: "기준 간판 '파리바게뜨 대치점' · 번호판 끝 4568" },
      { t: tp.preset, n: 3, title: "P단 정차 · 간판 앞 12m 운전자 원탭 지정", sub: "위치 오차 구간: 자동 거리 문구 대신 [간판 앞/정면/뒤]" },
      { t: tp.unlock, n: 4, title: "주변 확인 완료 → 실제 SDK 제어", sub: "Door.setDoorLock(전체 도어, false) · Window.setWindowPosition(2/10) → onComplete" },
      { t: tp.wrong, n: "!", title: "차도 쪽 문 열림 → 즉시 경고", sub: "Door.registerDoorPosition(ROW_2_LEFT) 실제 콜백 · 동승자 웹 '그 문은 차도 쪽이에요'" },
      { t: tp.doorfr, n: 5, title: "조수석 문 열림 → 독서등 ON", sub: "Light.setReadingLightSwitch(ROW_1_RIGHT) → onComplete(ON)" },
      { t: tp.seated, n: 5, title: "착좌 감지 → \"지수님, 반갑습니다\"", sub: "Seat.registerSeatOccupancy 실제 콜백 · 동승자 웹 환대 카드" },
      { t: tp.complete, n: 6, title: "탑승 확인 → 창문·독서등 원복 · 링크 즉시 파기", sub: "벨트 신호 없는 환경의 수동 종료 경로 · DELETE /api/v1/sessions/:id" }
    ],
    events: [
      { t: tp.start + 200, label: "NaviHelper.requestRoute()", hold: 5000 },
      { t: tp.preset + 100, label: "gear=P · stoppedInPark=true · manualPreset=AHEAD_12M", hold: 6000 },
      { t: tp.unlock + 300, label: "DOOR_LOCK onComplete(locked=false) · WINDOW_POS raw=2 → 20%", hold: 5500 },
      { t: tp.wrong + 200, label: "door[ROW_2_LEFT]=OPEN → CRITICAL TTS", hold: 5000 },
      { t: tp.doorfr + 200, label: "door[ROW_1_RIGHT]=OPEN → READING_LIGHTS_SWITCH onComplete", hold: 3800 },
      { t: tp.seated + 200, label: "seat[ROW_1_RIGHT]=OCCUPIED → welcome", hold: 5000 },
      { t: tp.complete + 300, label: "WINDOW_POS=0 · READING_LIGHTS=OFF · SESSION_SHREDDED", hold: 7000 }
    ]
  });
}
if (args.sim) {
  const out = path.join(work, "recording_sim.mp4");
  transcode(args.sim, out, "crop=2560:1200:0:170,scale=1284:602");
  const tp = taps(args.simTaps);
  const len = Number(args.simMs || 18000);
  S.demoB = { start: cursor, end: cursor + len }; cursor += len;
  demos.push({
    scene: "demoB", video: "recording_sim.mp4", real: false, offsetMs: Number(args.simOffset || -300), box: { w: 1284, h: 602, top: 170 },
    tag: "Android Automotive 에뮬레이터 · sim 빌드 (신호 주입 시나리오) — 위치 기반 자동 접근·정차", pill: "시뮬레이션 주행 · 같은 코드",
    credit: "접근·정차 자동 판정 (FusedLocation + 방향지시등 + 기어 P) — 시뮬레이션 주행 발췌",
    webStates: readTl(args.simTimeline).filter((r) => r.t <= len + 500),
    captions: [
      { t: tp.start, n: 1, title: "픽업 시작 · 시뮬레이션 주행", sub: "정차 지점 남쪽 300m에서 접근" },
      { t: tp.drive + 3800, n: 2, title: "접근 감지 → 동승자에게 알림", sub: "반경 80m + 감속 + 우측 방향지시등 · 도착 전 공조 사전 설정" },
      { t: tp.drive + 9400, n: 3, title: "P단 정차 확인 → \"차가 멈췄어요\"", sub: "기어 P + 차속 0 일 때만 · 간판 기준 앞 12m 자동 안내" }
    ],
    events: [
      { t: tp.drive + 100, label: "gear=D · speed 42→0 km/h · FusedLocation", hold: 9500 },
      { t: tp.drive + 4000, label: "turnSignal=RIGHT · 70m → approach", hold: 5500 },
      { t: tp.drive + 9600, label: "gear=P · stoppedInPark=true → STEP4", hold: 8000 }
    ]
  });
}
S.arch = { start: cursor, end: cursor + 15000 }; cursor += 15000;
S.end = { start: cursor, end: cursor + 8000 }; cursor += 8000;
const total = cursor;
fs.writeFileSync(path.join(work, "timeline.json"), JSON.stringify({ scenes: S, web: "web.html", demos }, null, 1));
fs.copyFileSync(path.join(repo, "web-companion", "index.html"), path.join(work, "web.html"));
fs.copyFileSync(path.join(here, "composition.html"), path.join(work, "composition.html"));

// ---- 2) 프레임 캡처 (정적 서버는 캡처 프로세스 안에서) -------------------------------------------
const port = Number(args.port || 8787);
const frames = path.join(work, "frames"); fs.rmSync(frames, { recursive: true, force: true });
execFileSync("node", [path.join(here, "cdp_capture.mjs"), "--serve", work, "--servePort", String(port), "--url", `http://127.0.0.1:${port}/composition.html`,
  "--out", frames, "--fps", String(fps), "--duration", String(total / 1000), "--width", "1920", "--height", "1080", "--port", String(Number(args.cdpPort || 9333))], { stdio: "inherit" });

// ---- 3) 내레이션 믹스 + 인코딩 ---------------------------------------------------------------------
const nar = args.narration ? path.resolve(args.narration) : null;
const A = S.demoA, B = S.demoB; const tpA = taps(args.pleosTaps), tpB = taps(args.simTaps);
const clips = nar ? [
  ["n1_title.wav", S.title.start + 600], ["n2_three.wav", S.three.start + 500],
  ...(A ? [["n13_pleos_intro.wav", A.start + 300], ["n11_preset.wav", A.start + tpA.preset + 200], ["n6b_unlock.wav", A.start + tpA.unlock + 300],
           ["n7_door.wav", A.start + tpA.wrong + 200], ["n8b_board.wav", A.start + tpA.doorfr + 200], ["n12_manual.wav", A.start + tpA.complete + 200]] : []),
  ...(B ? [["n14_sim_intro.wav", B.start + 300], ["n4_approach.wav", B.start + tpB.drive + 4200], ["n5_stop.wav", B.start + tpB.drive + 9800]] : []),
  ["n9_arch.wav", S.arch.start + 600], ["n10_end.wav", S.end.start + 800]
].filter(([f]) => fs.existsSync(path.join(nar, f))) : [];
const out = path.resolve(args.out || path.join(work, "majung_demo.mp4"));
const ff = ["-v", "error", "-y", "-framerate", String(fps), "-i", path.join(frames, "f%05d.jpg")];
if (clips.length) {
  clips.forEach(([f]) => ff.push("-i", path.join(nar, f)));
  const filter = clips.map((_, i) => `[${i + 1}:a]adelay=${clips[i][1]}|${clips[i][1]}[a${i}]`).join(";") + ";" + clips.map((_, i) => `[a${i}]`).join("") + `amix=inputs=${clips.length}:normalize=0:dropout_transition=0,apad[aout]`;
  ff.push("-filter_complex", filter, "-map", "0:v", "-map", "[aout]", "-c:a", "aac", "-b:a", "160k", "-shortest");
}
ff.push("-c:v", "libx264", "-preset", "slow", "-crf", "19", "-pix_fmt", "yuv420p", "-r", String(fps), "-movflags", "+faststart", "-t", String(total / 1000), out);
execFileSync("ffmpeg", ff, { stdio: "inherit" });
console.log("rendered:", out, execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration,size", "-of", "csv=p=0", out]).toString().trim());
