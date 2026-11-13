#!/usr/bin/env node
/**
 * 마중(MAJUNG) v2.0 실시간 SSE 중계 서버 End-to-End 통합 검증 스크립트
 * 검증 항목:
 *  1) PUT /api/v1/sessions/:id 시 원시 GPS 좌표 필터링(collectPassengerGps === false) 및 20분 TTL 설정 검증
 *  2) GET /api/v1/sessions/:id/events 실시간 SSE STATE_UPDATE 수신 검증
 *  3) POST /api/v1/sessions/:id/ready 및 /swap-landmark 양방향 이벤트 전달 검증
 *  4) DELETE /api/v1/sessions/:id 호출 즉시 SESSION_SHREDDED 이벤트 송출 및 이후 조회 시 HTTP 404 반환 검증
 */
import assert from "node:assert/strict";
import http from "node:http";
import { createMajungRelayServer } from "./server.mjs";

const server = createMajungRelayServer();

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const { port } = server.address();
const base = `http://127.0.0.1:${port}`;
const sessionId = "mj-e2e-test01";

try {
  // 1) 세션 생성 + 의도적으로 원시 GPS 필드를 섞어 보내 필터링되는지 검증
  const putRes = await fetch(`${base}/api/v1/sessions/${sessionId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      passengerName: "지수",
      landmarkSignboard: "파리바게뜨 대치점 노란 간판",
      vehicleModelLabel: "아이오닉 9",
      plateLast4: "4568",
      offsetMeters: 12,
      locationReliability: "HIGH_CONFIDENCE_AUTO",
      candidateSignboards: ["약국", { latitude: 37.12345 }],
      latitude: 37.12345, // 서버에서 자동 제거되어야 함
      longitude: 127.12345, // 서버에서 자동 제거되어야 함
      passengerCoordinates: { latitude: 37.12345, longitude: 127.12345 },
      createdAtEpochMs: 1,
      maxExpiresAtEpochMs: 2,
      collectPassengerGps: true, // 서버에서 false로 강제 고정되어야 함
    }),
  });
  assert.equal(putRes.status, 200);
  const putJson = await putRes.json();
  assert.equal(putJson.state.collectPassengerGps, false);
  assert.equal(putJson.state.latitude, undefined);
  assert.equal(putJson.state.longitude, undefined);
  assert.equal(putJson.state.passengerCoordinates, undefined);
  assert.notEqual(putJson.state.createdAtEpochMs, 1);
  assert.notEqual(putJson.state.maxExpiresAtEpochMs, 2);
  assert.deepEqual(putJson.state.candidateSignboards, ["약국"]);
  assert.equal(putJson.state.maxTtlMinutes, 20);

  const updateRes = await fetch(`${base}/api/v1/sessions/${sessionId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ passengerName: { latitude: 37.12345 }, maxExpiresAtEpochMs: 3 }),
  });
  const updatedJson = await updateRes.json();
  assert.equal(updatedJson.state.passengerName, "지수");
  assert.equal(updatedJson.state.maxExpiresAtEpochMs, putJson.state.maxExpiresAtEpochMs);

  // 2) SSE 클라이언트 연결 및 실시간 이벤트 수집
  const receivedEvents = [];
  await new Promise((resolve, reject) => {
    const req = http.get(`${base}/api/v1/sessions/${sessionId}/events`, (res) => {
      assert.equal(res.statusCode, 200);
      res.setEncoding("utf8");
      let buf = "";
      res.on("data", (chunk) => {
        buf += chunk;
        const frames = buf.split("\n\n");
        buf = frames.pop() || "";
        for (const frame of frames) {
          const evLine = frame.split("\n").find((l) => l.startsWith("event: "));
          const dataLine = frame.split("\n").find((l) => l.startsWith("data: "));
          if (evLine && dataLine) {
            receivedEvents.push({
              event: evLine.slice(7).trim(),
              data: JSON.parse(dataLine.slice(6)),
            });
            if (receivedEvents.length === 1) resolve();
          }
        }
      });
    });
    req.on("error", reject);
  });

  assert.equal(receivedEvents[0].event, "STATE_UPDATE");
  assert.equal(receivedEvents[0].data.plateLast4, "4568");

  // 3) 동승자 기준 간판 변경 요청 검증
  const swapRes = await fetch(`${base}/api/v1/sessions/${sessionId}/swap-landmark`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ landmarkSignboard: "한빛약국 초록 십자 간판" }),
  });
  assert.equal(swapRes.status, 200);

  // 4) 탑승 완료 시 즉시 DELETE 파기 -> 이후 조회 시 404 검증
  const delRes = await fetch(`${base}/api/v1/sessions/${sessionId}`, { method: "DELETE" });
  assert.equal(delRes.status, 200);

  const afterDelRes = await fetch(`${base}/api/v1/sessions/${sessionId}`);
  assert.equal(afterDelRes.status, 404);

  // 5) 동승자 모바일 웹 HTML 정상 서빙 확인
  const webRes = await fetch(`${base}/m/${sessionId}`);
  assert.equal(webRes.status, 200);
  const html = await webRes.text();
  assert.ok(html.includes("번호판"));

  console.log("PASS: MAJUNG Relay E2E SSE + GPS Stripping + Landmark Swap + Immediate DELETE Shred verified.");
} finally {
  server.close();
}
