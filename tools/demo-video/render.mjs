#!/usr/bin/env node
/**
 * 마중 시연 영상 렌더 파이프라인 (의존성: node 22+, ffmpeg, Google Chrome)
 *
 *   node tools/demo-video/render.mjs --work /path/to/work --recording recording_raw.mp4 --timeline timeline.jsonl \
 *        --narration /path/to/narration --out docs/demo/majung_demo.mp4
 *
 * 1) 에뮬레이터 녹화(2560×1440)에서 앱 영역(y 170~1370)만 잘라 1284×602 · 30fps 로 변환
 * 2) 중계 서버 SSE 로그 → 동승자 웹 상태 타임라인, 자막·신호 칩 타임라인 → timeline.json
 * 3) 로컬 정적 서버에서 composition.html 을 열고 CDP 로 프레임 캡처 (결정적 t 기반 렌더링)
 * 4) 프레임 + macOS say 내레이션 믹스 → H.264/AAC mp4
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "..", "..");
const args = Object.fromEntries(process.argv.slice(2).map((a, i, arr) => a.startsWith("--") ? [a.slice(2), arr[i + 1]] : []).filter(Boolean));
const work = path.resolve(args.work);
const fps = Number(args.fps || 30);
fs.mkdirSync(work, { recursive: true });

// ---- 1) 녹화 변환 --------------------------------------------------------------------------
const recIn = path.resolve(args.recording);
const recOut = path.join(work, "recording.mp4");
execFileSync("ffmpeg", ["-v", "error", "-y", "-i", recIn, "-vf", `crop=2560:1200:0:170,scale=1284:602,fps=${fps}`,
  "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an", recOut]);
const recDur = Number(execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", recOut]).toString().trim());

// ---- 2) 타임라인 ------------------------------------------------------------------------------
const DEMO_MS = Math.min(Math.round(recDur * 1000), Number(args.demoMs || 48000));
const scenes = { title: [0, 11000], three: [11000, 26000], demo: [26000, 26000 + DEMO_MS], arch: [26000 + DEMO_MS, 26000 + DEMO_MS + 15000], end: [26000 + DEMO_MS + 15000, 26000 + DEMO_MS + 23000] };
const S = Object.fromEntries(Object.entries(scenes).map(([k, [s, e]]) => [k, { start: s, end: e }]));
const total = S.end.end;

const webStates = fs.readFileSync(path.resolve(args.timeline), "utf-8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l))
  .map((r) => ({ t: r.t, event: r.event, data: r.data }));
// 탭 시각(녹화 기준, ms)과 자막
const taps = { start: 2000, drive: 5000, unlock: 24000, wrong: 30000, board: 36000 };
Object.assign(taps, Object.fromEntries(Object.entries(args).filter(([k]) => k.startsWith("tap_")).map(([k, v]) => [k.slice(4), Number(v)])));
const captions = [
  { t: taps.start, n: 1, title: "픽업 시작 · 링크 발급", sub: "기준 간판 합의 · 차종·번호판 끝 4자리 · 일회성 웹 링크(최대 20분)" },
  { t: taps.drive + 3800, n: 2, title: "접근 감지 → 동승자에게 알림", sub: "반경 80m + 감속 + 우측 방향지시등 · 도착 전 공조 사전 설정" },
  { t: taps.drive + 9400, n: 3, title: "P단 정차 확인 → \"차가 멈췄어요\"", sub: "기어 P + 차속 0 일 때만 · 간판 기준 앞 12m 안내" },
  { t: taps.unlock, n: 4, title: "주변 확인 완료 · 승차 맞이 (유일한 수동 버튼)", sub: "조수석 잠금 해제 · 창문 25% · 동승자 웹 \"지금 타도 돼요\"" },
  { t: taps.wrong, n: "!", title: "차도 쪽 문 열림 → 즉시 음성 경고", sub: "Door.registerDoorPosition(ROW_2_LEFT) · 운전 부하 게이트 우회" },
  { t: taps.board, n: 5, title: "문 열림 → 독서등 · 착좌 → 환대 인사", sub: "하향등 ON 야간 · \"지수님, 반갑습니다\"" },
  { t: taps.board + 6700, n: 6, title: "벨트 체결 → 공유 링크 자동 파기", sub: "창문·독서등·공조 원복 · DELETE /api/v1/sessions/:id" }
];
const events = [
  { t: taps.drive + 100, label: "gear=D · speed 42→0 km/h", hold: 9500 },
  { t: taps.drive + 4000, label: "turnSignal=RIGHT · 70m", hold: 5500 },
  { t: taps.drive + 9600, label: "gear=P · stoppedInPark=true", hold: 14000 },
  { t: taps.unlock + 200, label: "setDoorLock(ROW_1_RIGHT,false) · setWindowPosition(25)", hold: 5500 },
  { t: taps.wrong + 100, label: "door[ROW_2_LEFT]=OPEN → CRITICAL TTS", hold: 5500 },
  { t: taps.board + 800, label: "door[ROW_1_RIGHT]=OPEN → readingLight", hold: 2200 },
  { t: taps.board + 3000, label: "seat[ROW_1_RIGHT]=OCCUPIED → welcome TTS", hold: 2200 },
  { t: taps.board + 6700, label: "belt[ROW_1_RIGHT]=BUCKLED → shred", hold: 8000 }
];
fs.writeFileSync(path.join(work, "timeline.json"), JSON.stringify({ scenes: S, recordingOffsetMs: Number(args.offset || 0), video: "recording.mp4", web: "web.html", events, captions, webStates }, null, 1));
fs.copyFileSync(path.join(repo, "web-companion", "index.html"), path.join(work, "web.html"));
fs.copyFileSync(path.join(here, "composition.html"), path.join(work, "composition.html"));

// ---- 3) 정적 서버 + 프레임 캡처 --------------------------------------------------------------
// 정적 서버는 캡처 프로세스 안에서 띄운다 (execFileSync 로 부모 이벤트 루프가 막히므로 부모가 서빙하면 안 됨)
const port = Number(args.port || 8787);
const frames = path.join(work, "frames"); fs.rmSync(frames, { recursive: true, force: true });
execFileSync("node", [path.join(here, "cdp_capture.mjs"), "--serve", work, "--servePort", String(port), "--url", `http://127.0.0.1:${port}/composition.html`,
  "--out", frames, "--fps", String(fps), "--duration", String(total / 1000), "--width", "1920", "--height", "1080", "--port", String(Number(args.cdpPort || 9333))], { stdio: "inherit" });

// ---- 4) 내레이션 믹스 + 인코딩 -----------------------------------------------------------------
const nar = args.narration ? path.resolve(args.narration) : null;
const clips = nar ? [
  ["n1_title.wav", S.title.start + 600], ["n2_three.wav", S.three.start + 500],
  ["n3_start.wav", S.demo.start + taps.start], ["n4_approach.wav", S.demo.start + taps.drive + 4200], ["n5_stop.wav", S.demo.start + taps.drive + 12600],
  ["n6_unlock.wav", S.demo.start + taps.unlock + 1200], ["n7_door.wav", S.demo.start + taps.wrong + 200], ["n8_board.wav", S.demo.start + taps.board + 200],
  ["n9_arch.wav", S.arch.start + 600], ["n10_end.wav", S.end.start + 800]
].filter(([f]) => fs.existsSync(path.join(nar, f))) : [];
const out = path.resolve(args.out || path.join(work, "majung_demo.mp4"));
const ff = ["-v", "error", "-y", "-framerate", String(fps), "-i", path.join(frames, "f%05d.jpg")];
let filter = "";
if (clips.length) {
  clips.forEach(([f]) => ff.push("-i", path.join(nar, f)));
  filter = clips.map((_, i) => `[${i + 1}:a]adelay=${clips[i][1]}|${clips[i][1]},volume=1.0[a${i}]`).join(";") + ";" + clips.map((_, i) => `[a${i}]`).join("") + `amix=inputs=${clips.length}:normalize=0:dropout_transition=0,apad[aout]`;
  ff.push("-filter_complex", filter, "-map", "0:v", "-map", "[aout]", "-c:a", "aac", "-b:a", "160k", "-shortest");
}
ff.push("-c:v", "libx264", "-preset", "slow", "-crf", "19", "-pix_fmt", "yuv420p", "-r", String(fps), "-movflags", "+faststart", "-t", String(total / 1000), out);
execFileSync("ffmpeg", ff, { stdio: "inherit" });
console.log("rendered:", out, execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration,size", "-of", "csv=p=0", out]).toString().trim());
