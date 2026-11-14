#!/usr/bin/env node
/**
 * 마중 시연 영상 렌더 파이프라인 (의존성: node 22+, ffmpeg, Google Chrome)
 *
 *   node tools/demo-video/render.mjs --work <dir> \
 *     --pleos <recording.webm> --pleosTimeline <timeline.jsonl> --pleosTaps <taps.json> \
 *     [--pleosCrop 1067:756:533:56] [--pleosOffset -300] \
 *     --narration <dir> --out docs/demo/majung_demo.mp4
 *
 * 장면: 타이틀 → 3가지 → [데모: Pleos Connect 에뮬레이터 실녹화(런처 오른쪽 앱 영역 크롭) + 동승자 웹 동기 재생] → 구조·검증 → 엔딩
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
let cursor = 26000; // title 11s + three 15s
const S = { title: { start: 0, end: 11000 }, three: { start: 11000, end: 26000 } };

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
    tag: "Pleos Connect v2.1.0 에뮬레이터, pleos 빌드 (SDK 실연동), 런처 오른쪽 앱 영역", pill: "Pleos Connect 전용 에뮬레이터 실녹화",
    credit: "차량 신호: Virtual Console → 실제 Vehicle SDK 콜백. 차량 위치: 에뮬레이터에 주입 수단이 없어 디버그 빌드의 adb 훅으로 넣음",
    webStates: readTl(args.pleosTimeline),
    // 동승자 웹 재생에서 핀 → 보낸 자리 → (수락 뒤) 제거를 흉내 낼 구간과 좌표 (pleos_flow.sh 의 PROP_LAT/PROP_LNG 와 같아야 한다)
    pick: { t0: at("pick", at("suggest") - 4000), t1: at("suggest"), tEnd: at("accept"), lat: Number(args.pickLat || 37.498930), lng: Number(args.pickLng || 127.062050) },
    captions: [
      { t: at("start"), n: 1, title: "픽업 시작, 링크 보내기", sub: "NaviHelper.requestRoute로 내비 경로 설정, 동승자에게 일회성 지도 링크" },
      { t: at("car320"), n: 2, title: "지도와 지금 누를 버튼만", sub: "동승자 위치와 승차 지점, 내 차. 가는 동안 누를 버튼은 '여기 못 서요'뿐" },
      { t: at("pax"), n: 2, title: "동승자 위치가 차에 보입니다", sub: "동승자 웹이 허용한 동안만 세션 메모리에, 탑승 확인 즉시 삭제" },
      { t: at("cantstop"), n: 3, title: "여기 못 서요", sub: "건너편 (U턴), 앞으로 30 m, 한 바퀴 중 하나" },
      { t: at("option"), n: 3, title: "앞으로 30 m: 승차 지점 이동, 내비 재경로", sub: "동승자 웹에 '운전자: 앞으로 30 m'와 새 승차 지점" },
      { t: at("pick", at("suggest") - 4000), n: 4, title: "동승자가 지도에서 탈 자리를 고릅니다", sub: "지도를 움직여 핀(마중 심볼)을 맞추고 '여기서 탈게요'" },
      { t: at("suggest"), n: 4, title: "고른 좌표가 차량에 카드로 도착", sub: "POST /suggest {PICKUP_HERE, lat, lng} → '승차 지점에서 29 m'" },
      { t: at("accept"), n: 4, title: "수락: 승차 지점이 그 자리로, 내비 재경로", sub: "동승자 웹 '운전자가 수락했어요', 현대 엠블럼이 고른 자리로" },
      { t: at("near"), n: 5, title: "접근 감지", sub: "반경 80 m, 감속, 우측 방향지시등 (Vehicle SDK 실제 콜백)" },
      { t: at("stopped", at("stop")), n: 5, title: "P단 정차 확인", sub: "기어 P + 차속 0 일 때만 동승자 웹이 '차가 멈췄어요'로" },
      { t: at("night"), n: 5, title: "하향등 ON → 밤 화면", sub: "Light.registerLowBeam 콜백으로 낮/밤 토큰 전환" },
      { t: at("unlock"), n: 6, title: "주변 확인 끝, 문 열기", sub: "Door.setDoorLock(false), Window.setWindowPosition(2/10) → onComplete" },
      { t: at("doorfr"), n: 6, title: "조수석 문 열림 → 독서등", sub: "Light.setReadingLightSwitch(ROW_1_RIGHT) → onComplete(ON)" },
      { t: at("seated"), n: 6, title: "착좌 감지 → \"지수님, 반갑습니다\"", sub: "Seat.registerSeatOccupancy 실제 콜백, 동승자 웹 환대 카드" },
      { t: at("complete"), n: 7, title: "탑승 확인, 종료", sub: "창문·독서등 원복, DELETE /sessions/:id → 링크와 위치 정보 즉시 삭제" }
    ],
    events: [
      { t: at("start") + 200, label: "NaviHelper.requestRoute()", hold: 5000 },
      { t: at("car320") + 100, label: "gear=D · speed=32 km/h [AAOS] · carLat/carLng", hold: 5000 },
      { t: at("pax") + 100, label: "PASSENGER_LOCATION (SSE)", hold: 4000 },
      { t: at("option") + 200, label: "pickupVersion=2 · requestRoute()", hold: 5000 },
      { t: at("suggest") + 200, label: "PASSENGER_SUGGESTION {PICKUP_HERE, lat, lng}", hold: 3500 },
      { t: at("accept") + 200, label: "suggestionReply=ACCEPTED · pickup → (lat,lng) · requestRoute()", hold: 4500 },
      { t: at("near") + 100, label: "turnSignal=RIGHT · 60 m → approach", hold: 4500 },
      { t: at("stopped", at("stop")) + 100, label: "gear=P · stoppedInPark=true → STEP4", hold: 5000 },
      { t: at("night") + 100, label: "lowBeam=ON → night tokens", hold: 3500 },
      { t: at("unlock") + 300, label: "DOOR_LOCK onComplete(false) · WINDOW_POS raw=2 → 20%", hold: 5500 },
      { t: at("doorfr") + 200, label: "door[ROW_1_RIGHT]=OPEN → READING_LIGHTS onComplete", hold: 3800 },
      { t: at("seated") + 200, label: "seat[ROW_1_RIGHT]=OCCUPIED → welcome", hold: 4500 },
      { t: at("complete") + 300, label: "WINDOW_POS=0 · READING_LIGHTS=OFF · SESSION_SHREDDED", hold: 6000 }
    ]
  });
}
S.arch = { start: cursor, end: cursor + 15000 }; cursor += 15000;
S.end = { start: cursor, end: cursor + 8000 }; cursor += 8000;
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
  ["n1_title.wav", S.title.start + 600], ["n2_three.wav", S.three.start + 500],
  ...(A ? [["n20_map.wav", atA("car320") + 300], ["n21_cantstop.wav", atA("cantstop") + 300], ["n22_suggest.wav", atA(tpA.pick ? "pick" : "suggest") + 300],
           ["n23_stop.wav", atA(tpA.stopped ? "stopped" : "stop") + 300], ["n24_unlock.wav", atA("unlock") + 300], ["n25_board.wav", atA("seated") + 300]] : []),
  ["n26_arch.wav", S.arch.start + 600], ["n10_end.wav", S.end.start + 800]
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
