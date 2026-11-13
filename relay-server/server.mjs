#!/usr/bin/env node
/**
 * 마중(MAJUNG) v2.0 — 무의존성 실시간 SSE & 일회성 안심 웹 중계 서버 (Node.js 18+)
 * 실행: PORT=8080 node relay-server/server.mjs
 *
 * [개정안_v2 개인정보·토큰 정책 100% 반영]
 * 1) 동승자 단말의 원시 GPS(latitude, longitude, gps, passengerLat 등) 수집·저장 원천 차단
 * 2) 탑승 확인 완료 또는 운전자 수동 종료(DELETE /api/v1/sessions/:id) 시 메모리에서 즉시 파기 및 SSE SESSION_SHREDDED 송출
 * 3) 종료 누락 방치 시에도 발급 시점 기준 최대 20분(1,200,000ms) 경과 시 자동 만료 파기
 */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 8080);
const MAX_SESSION_TTL_MS = 20 * 60 * 1000; // 통일 기준: 최대 20분 자동 만료
const WEB_HTML_PATH = path.resolve(__dirname, "../web-companion/index.html");

/** @type {Map<string, { state: any, subscribers: Set<http.ServerResponse>, timer: NodeJS.Timeout }>} */
const ephemeralSessions = new Map();

function sanitizeIncomingPayload(rawPayload) {
  if (!rawPayload || typeof rawPayload !== "object" || Array.isArray(rawPayload)) return {};
  const safeFields = {};
  const strings = ["passengerName", "pitstopName", "landmarkSignboard", "vehicleModelLabel",
    "plateLast4", "offsetGuideText", "locationReliability", "runtimeSourceBadge", "phase", "gear", "signalSource"];
  const numbers = ["walkingMetersWithoutCrossing", "remainingSeconds", "issuedAtEpochMs", "speedBucketKmh"];
  // 차량 신호 요약(VehicleFacts). 차량의 원시 좌표는 받지 않고, 버킷화된 거리(10m 단위)만 통과시킨다.
  const booleans = ["boardingDoorUnlocked", "welcomeGreetingActive",
    "stoppedInPark", "trafficSideDoorOpen", "passengerDoorOpen", "passengerSeated", "passengerBuckled"];
  for (const key of strings) if (typeof rawPayload[key] === "string") safeFields[key] = rawPayload[key];
  for (const key of numbers) if (typeof rawPayload[key] === "number" && Number.isFinite(rawPayload[key])) safeFields[key] = rawPayload[key];
  for (const key of booleans) if (typeof rawPayload[key] === "boolean") safeFields[key] = rawPayload[key];
  if (rawPayload.offsetMeters === null || (typeof rawPayload.offsetMeters === "number" && Number.isFinite(rawPayload.offsetMeters))) {
    safeFields.offsetMeters = rawPayload.offsetMeters;
  }
  if (rawPayload.distanceBucketMeters === null || (typeof rawPayload.distanceBucketMeters === "number" && Number.isFinite(rawPayload.distanceBucketMeters))) {
    safeFields.distanceBucketMeters = rawPayload.distanceBucketMeters;
  }
  if (Array.isArray(rawPayload.candidateSignboards)) {
    safeFields.candidateSignboards = rawPayload.candidateSignboards.filter((value) => typeof value === "string");
  }
  safeFields.collectPassengerGps = false;
  return safeFields;
}

function shredSession(sessionId, reason = "IMMEDIATE_END") {
  const entry = ephemeralSessions.get(sessionId);
  if (!entry) return false;
  clearTimeout(entry.timer);
  const msg = `event: SESSION_SHREDDED\ndata: ${JSON.stringify({ sessionId, reason, shreddedAt: Date.now() })}\n\n`;
  for (const sub of entry.subscribers) {
    sub.write(msg);
    sub.end();
  }
  ephemeralSessions.delete(sessionId);
  return true;
}

function upsertSession(sessionId, payload) {
  let entry = ephemeralSessions.get(sessionId);
  const now = Date.now();
  if (!entry) {
    entry = {
      state: {
        sessionId,
        createdAtEpochMs: now,
        maxExpiresAtEpochMs: now + MAX_SESSION_TTL_MS,
        collectPassengerGps: false,
      },
      subscribers: new Set(),
      timer: setTimeout(() => shredSession(sessionId, "TTL_20MIN_EXPIRED"), MAX_SESSION_TTL_MS),
    };
    ephemeralSessions.set(sessionId, entry);
  }
  const clean = sanitizeIncomingPayload(payload);
  entry.state = {
    ...entry.state,
    ...clean,
    sessionId,
    maxTtlMinutes: 20,
    updatedAtEpochMs: now,
  };

  const msg = `event: STATE_UPDATE\ndata: ${JSON.stringify(entry.state)}\n\n`;
  for (const sub of entry.subscribers) {
    sub.write(msg);
  }
  return entry.state;
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 64 * 1024) reject(new Error("Payload too large"));
    });
    req.on("end", () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (err) {
        reject(err);
      }
    });
    req.on("error", reject);
  });
}

export function createMajungRelayServer() {
  return http.createServer(async (req, res) => {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type");

    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);

    // 헬스체크
    if (req.method === "GET" && url.pathname === "/healthz") {
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
      res.end(
        JSON.stringify({
          ok: true,
          activeSessionCount: ephemeralSessions.size,
          maxTtlMinutes: 20,
          collectPassengerGps: false,
        })
      );
      return;
    }

    // 0) 로컬 시연·테스트 보조: 가장 최근 세션 id (루프백 요청에서만 응답, 토큰은 노출하지 않음)
    if (req.method === "GET" && url.pathname === "/api/v1/sessions/_latest") {
      const remote = req.socket.remoteAddress || "";
      const isLoopback = remote === "127.0.0.1" || remote === "::1" || remote === "::ffff:127.0.0.1";
      const latest = [...ephemeralSessions.keys()].pop();
      if (!isLoopback || !latest) {
        res.writeHead(404, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify({ error: "NOT_FOUND" }));
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
      res.end(JSON.stringify({ sessionId: latest }));
      return;
    }

    // 1) 무설치 동승자 안심 웹 서빙 (/, /m/:sessionId)
    if (req.method === "GET" && (url.pathname === "/" || url.pathname.startsWith("/m/"))) {
      const html = fs.existsSync(WEB_HTML_PATH)
        ? fs.readFileSync(WEB_HTML_PATH, "utf-8")
        : "<h1>MAJUNG Companion Web</h1>";
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end(html);
      return;
    }

    // 2) 실시간 SSE 스트림 구독 (/api/v1/sessions/:id/events)
    const eventsMatch = url.pathname.match(/^\/api\/v1\/sessions\/([^/]+)\/events$/);
    if (req.method === "GET" && eventsMatch) {
      const sessionId = decodeURIComponent(eventsMatch[1]);
      const entry = ephemeralSessions.get(sessionId);
      if (!entry) {
        res.writeHead(404, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify({ error: "SESSION_NOT_FOUND_OR_SHREDDED", sessionId }));
        return;
      }

      res.writeHead(200, {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-cache, no-transform",
        Connection: "keep-alive",
      });
      res.write(`event: STATE_UPDATE\ndata: ${JSON.stringify(entry.state)}\n\n`);
      entry.subscribers.add(res);
      req.on("close", () => entry.subscribers.delete(res));
      return;
    }

    // 3) 세션 단건 조회 / 갱신(PUT) / 즉시 파기(DELETE) (/api/v1/sessions/:id)
    const sessionMatch = url.pathname.match(/^\/api\/v1\/sessions\/([^/]+)$/);
    if (sessionMatch) {
      const sessionId = decodeURIComponent(sessionMatch[1]);

      if (req.method === "GET") {
        const entry = ephemeralSessions.get(sessionId);
        if (!entry) {
          res.writeHead(404, { "Content-Type": "application/json; charset=utf-8" });
          res.end(JSON.stringify({ error: "SESSION_NOT_FOUND_OR_SHREDDED", sessionId }));
          return;
        }
        res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify(entry.state));
        return;
      }

      if (req.method === "PUT") {
        try {
          const payload = await readJsonBody(req);
          const updated = upsertSession(sessionId, payload);
          res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
          res.end(JSON.stringify({ ok: true, state: updated }));
        } catch {
          res.writeHead(400, { "Content-Type": "application/json; charset=utf-8" });
          res.end(JSON.stringify({ error: "INVALID_JSON" }));
        }
        return;
      }

      if (req.method === "DELETE") {
        const existed = shredSession(sessionId, "BOARDING_CONFIRMED_OR_MANUAL_END");
        res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify({ shredded: true, existed, sessionId }));
        return;
      }
    }

    // 4) 동승자 '기준 간판 앞 대기 완료' 원탭 응답 (/api/v1/sessions/:id/ready)
    const readyMatch = url.pathname.match(/^\/api\/v1\/sessions\/([^/]+)\/ready$/);
    if (req.method === "POST" && readyMatch) {
      const sessionId = decodeURIComponent(readyMatch[1]);
      const entry = ephemeralSessions.get(sessionId);
      if (!entry) {
        res.writeHead(404, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify({ error: "SESSION_SHREDDED" }));
        return;
      }
      try {
        const body = await readJsonBody(req);
        const safeNote = String(body.statusText || "기준 간판 앞에서 대기 중입니다.").slice(0, 80);
        entry.state.passengerStatusText = safeNote;
        const evt = `event: PASSENGER_READY\ndata: ${JSON.stringify({ statusText: safeNote })}\n\n`;
        for (const sub of entry.subscribers) sub.write(evt);
        res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify({ delivered: true, statusText: safeNote }));
      } catch {
        res.writeHead(400);
        res.end();
      }
      return;
    }

    // 5) 동승자 '대체 후보 간판으로 변경 제안' (/api/v1/sessions/:id/swap-landmark)
    const swapMatch = url.pathname.match(/^\/api\/v1\/sessions\/([^/]+)\/swap-landmark$/);
    if (req.method === "POST" && swapMatch) {
      const sessionId = decodeURIComponent(swapMatch[1]);
      const entry = ephemeralSessions.get(sessionId);
      if (!entry) {
        res.writeHead(404, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify({ error: "SESSION_SHREDDED" }));
        return;
      }
      try {
        const body = await readJsonBody(req);
        const nextLandmark = String(body.landmarkSignboard || "").trim().slice(0, 80);
        if (!nextLandmark) {
          res.writeHead(400);
          res.end();
          return;
        }
        entry.state.landmarkSignboard = nextLandmark;
        entry.state.offsetGuideText = `동승자 요청으로 기준 간판이 '${nextLandmark}'(으)로 변경되었습니다.`;
        const swapEvt = `event: LANDMARK_SWAP_REQUEST\ndata: ${JSON.stringify({ landmarkSignboard: nextLandmark })}\n\n`;
        const stateEvt = `event: STATE_UPDATE\ndata: ${JSON.stringify(entry.state)}\n\n`;
        for (const sub of entry.subscribers) {
          sub.write(swapEvt);
          sub.write(stateEvt);
        }
        res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify({ swapped: true, landmarkSignboard: nextLandmark }));
      } catch {
        res.writeHead(400);
        res.end();
      }
      return;
    }

    // 6) 단문 SMS 폴백 스텁 (/api/v1/sms-fallback)
    if (req.method === "POST" && url.pathname === "/api/v1/sms-fallback") {
      await readJsonBody(req).catch(() => ({}));
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
      res.end(JSON.stringify({ queuedSms: true }));
      return;
    }

    res.writeHead(404, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ error: "NOT_FOUND" }));
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const srv = createMajungRelayServer();
  srv.listen(PORT, "0.0.0.0", () => {
    console.log(`[MAJUNG v2.0 Ephemeral Relay] Listening on http://0.0.0.0:${PORT} (Max TTL: 20m, Immediate Shred on DELETE)`);
  });
}
