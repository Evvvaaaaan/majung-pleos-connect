#!/usr/bin/env node
/**
 * 마중 시연 영상 렌더 파이프라인 (의존성: node 22+, ffmpeg, Google Chrome)
 *
 *   node tools/demo-video/render.mjs --work <dir> \
 *     --pleos <recording.webm> --pleosTimeline <timeline.jsonl> --pleosTaps <taps.json> \
 *     [--pleosCrop 1067:756:533:56] [--pleosOffset -300] \
 *     --narration <dir> --out docs/demo/majung_demo.mp4
 *
 * 장면: 타이틀 → [데모: Pleos Connect 에뮬레이터 실녹화(런처 오른쪽 앱 영역 크롭) + 동승자 웹 동기 재생] → 엔딩.
 * 화면 글자는 단계 이름 한 줄만 두고 설명은 내레이션(v0~v9, ElevenLabs 음성)이 맡는다.
 * taps.json 은 tools/demo-video/pleos_flow.sh 가 남기는 단계별 ms(녹화 시작 기준)다.
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
const dur = (f) => Number(execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f]).toString().trim());
const readTl = (f) => fs.readFileSync(path.resolve(f), "utf-8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)).map((r) => ({ t: r.t, event: r.event, data: r.data }));

// ---- 1) 녹화 변환: 런처 오른쪽 앱 영역만 잘라 1200×850 (libx264: 짝수 크기) 으로 ---------------------------------------
function transcode(src, out, vf) {
  execFileSync("ffmpeg", ["-v", "error", "-y", "-i", path.resolve(src), "-vf", `${vf},fps=${fps}`, "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an", out]);
  return dur(out);
}
const demos = [];
let cursor = 5500; // 타이틀 5.5초 뒤 바로 화면 시연
const S = { title: { start: 0, end: 5500 } };

if (args.pleos) {
  const out = path.join(work, "recording_pleos.mp4");
  const crop = args.pleosCrop || "1067:756:533:56";
  const d = transcode(args.pleos, out, `crop=${crop},scale=1200:850`);
  const tp = JSON.parse(fs.readFileSync(path.resolve(args.pleosTaps), "utf-8"));
  const len = Math.min(Math.round(d * 1000), Number(args.pleosMs || Math.round(d * 1000)));
  S.demoA = { start: cursor, end: cursor + len }; cursor += len;
  const at = (k, dflt = 0) => (typeof tp[k] === "number" ? tp[k] : dflt);
  demos.push({
    scene: "demoA", video: "recording_pleos.mp4", real: true, offsetMs: Number(args.pleosOffset || -300), box: { w: 1200, h: 850, top: 90 },
    tag: "", pill: "Pleos Connect 에뮬레이터 실녹화", stepCount: 7,
    credit: "왼쪽 차량 화면 · 오른쪽 동승자 휴대폰",
    webStates: readTl(args.pleosTimeline),
    // 동승자 웹 재생에서 핀 → 보낸 자리 → (수락 뒤) 제거를 흉내 낼 구간과 좌표 (pleos_flow.sh 의 PROP_LAT/PROP_LNG 와 같아야 한다)
    pick: { t0: at("pick", at("suggest") - 4000), t1: at("suggest"), tEnd: at("accept"), lat: Number(args.pickLat || 37.498930), lng: Number(args.pickLng || 127.062050) },
    // 글자는 단계 이름 한 줄만. 설명은 내레이션이 맡는다.
    captions: [
      { t: at("start"), n: 1, title: "픽업 시작", sub: "" },
      { t: at("car320"), n: 2, title: "차와 동승자가 같은 지도를 봅니다", sub: "" },
      { t: at("cantstop"), n: 3, title: "여기 못 서요", sub: "" },
      { t: at("option"), n: 3, title: "승차 지점 이동", sub: "" },
      { t: at("pick", at("suggest") - 4000), n: 4, title: "동승자가 탈 자리를 고릅니다", sub: "" },
      { t: at("suggest"), n: 4, title: "차량에 제안 도착", sub: "" },
      { t: at("accept"), n: 4, title: "수락, 승차 지점 변경", sub: "" },
      { t: at("near"), n: 5, title: "접근 알림", sub: "" },
      { t: at("stopped", at("stop")), n: 5, title: "P단 정차 확인", sub: "" },
      { t: at("unlock"), n: 6, title: "주변 확인, 잠금 해제", sub: "" },
      { t: at("seated"), n: 6, title: "환대 인사", sub: "" },
      { t: at("complete"), n: 7, title: "탑승 완료, 공유 종료", sub: "" }
    ],
    events: []
  });
}
S.end = { start: cursor, end: cursor + 6500 }; cursor += 6500;
const total = cursor;
fs.writeFileSync(path.join(work, "timeline.json"), JSON.stringify({ scenes: S, web: "web.html", webQuery: args.webQuery || "?s=demo-session&demoLat=37.49893&demoLng=127.06164", demos }, null, 1));
fs.copyFileSync(path.join(repo, "web-companion", "index.html"), path.join(work, "web.html"));
fs.copyFileSync(path.join(here, "composition.html"), path.join(work, "composition.html"));

// ---- 2) 프레임 캡처 (정적 서버는 캡처 프로세스 안에서) -------------------------------------------
const port = Number(args.port || 8787);
const frames = path.join(work, "frames"); fs.rmSync(frames, { recursive: true, force: true });
execFileSync("node", [path.join(here, "cdp_capture.mjs"), "--serve", work, "--servePort", String(port), "--url", `http://127.0.0.1:${port}/composition.html`,
  "--out", frames, "--fps", String(fps), "--duration", String(total / 1000), "--width", "1920", "--height", "1080", "--port", String(Number(args.cdpPort || 9333))], { stdio: "inherit" });

// ---- 3) 내레이션 믹스 + 인코딩 ---------------------------------------------------------------------
const nar = args.narration ? path.resolve(args.narration) : null;
const A = S.demoA; const tpA = args.pleosTaps ? JSON.parse(fs.readFileSync(path.resolve(args.pleosTaps), "utf-8")) : {};
const atA = (k) => A.start + (tpA[k] || 0);
const clips = nar ? [
  ["v0_title.wav", S.title.start + 500],
  ...(A ? [["v1_start.wav", atA("start") + 300], ["v2_map.wav", atA("car320") + 300], ["v3_cantstop.wav", atA("cantstop") + 300], ["v4_pick.wav", atA("pick") + 300],
           ["v5_stop.wav", atA("stop") + 1200], ["v6_unlock.wav", atA("unlock") + 300], ["v7_seated.wav", atA("seated") + 300], ["v8_end.wav", atA("complete") + 300]] : []),
  ["v9_outro.wav", S.end.start + 800]
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
