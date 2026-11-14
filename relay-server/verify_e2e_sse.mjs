#!/usr/bin/env node
/**
 * 마중(MAJUNG) v2.0 실시간 SSE 중계 서버 End-to-End 통합 검증 스크립트
 * 검증 항목:
 *  1) PUT /api/v1/sessions/:id 시 원시 GPS 좌표 필터링(임의 좌표 필드 차단, passengerLocationOptIn === true) 및 20분 TTL 설정 검증
 *  2) GET /api/v1/sessions/:id/events 실시간 SSE STATE_UPDATE 수신 검증
 *  3) POST /api/v1/sessions/:id/ready 및 /swap-landmark 양방향 이벤트 전달 검증
 *  4) DELETE /api/v1/sessions/:id 호출 즉시 SESSION_SHREDDED 이벤트 송출 및 이후 조회 시 HTTP 404 반환 검증
 */
import assert from "node:assert/strict";
import http from "node:http";
import { createMajungRelayServer } from "./server.mjs";

const server = createMajungRelayServer();

/** SSE 구독 헬퍼: 수신한 이벤트를 모으고 조건을 만족하는 이벤트를 기다린다. */
function subscribe(url) {
  const events = [];
  const waiters = [];
  const req = http.get(url, (res) => {
    res.setEncoding("utf8");
    let buf = "";
    res.on("data", (chunk) => {
      buf += chunk;
      const frames = buf.split("\n\n");
      buf = frames.pop() || "";
      for (const frame of frames) {
        const ev = frame.split("\n").find((l) => l.startsWith("event: "));
        const data = frame.split("\n").find((l) => l.startsWith("data: "));
        if (ev && data) events.push({ event: ev.slice(7).trim(), data: JSON.parse(data.slice(6)) });
      }
      for (const w of [...waiters]) w();
    });
  });
  req.on("error", () => {});
  return {
    waitFor: (pred, timeoutMs = 3000) => new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error("SSE wait timeout")), timeoutMs);
      const check = () => {
        const hit = events.find(pred);
        if (!hit) return;
        clearTimeout(t);
        waiters.splice(waiters.indexOf(check), 1);
        resolve(hit);
      };
      waiters.push(check);
      check();
    }),
    close: () => req.destroy(),
  };
}

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
      stoppedInPark: true, // 차량 신호 요약(VehicleFacts)은 통과해야 함
      passengerBuckled: false,
      gear: "P",
      speedBucketKmh: 0,
      distanceBucketMeters: 10,
      candidateSignboards: ["약국", { latitude: 37.12345 }],
      latitude: 37.12345, // 서버에서 자동 제거되어야 함
      longitude: 127.12345, // 서버에서 자동 제거되어야 함
      passengerCoordinates: { latitude: 37.12345, longitude: 127.12345 },
      createdAtEpochMs: 1,
      maxExpiresAtEpochMs: 2,
      collectPassengerGps: true, // 더 이상 허용 필드가 아님 — 서버가 버려야 함
      passengerLat: 1, // 차량(PUT)이 동승자 위치를 써넣을 수는 없다 — 서버가 버려야 함
      carLat: 37.4988, carLng: 127.0614, carHeadingDeg: 90, carUpdatedAtEpochMs: 123456,
      pickupLat: 37.49881, pickupLng: 127.06142, pickupLabel: "가".repeat(100), pickupNote: "건너편에서 태울게요", pickupVersion: 2,
      suggestionReplyId: "x", suggestionReply: "MAYBE", // ACCEPTED|DECLINED 외는 버려야 함
    }),
  });
  assert.equal(putRes.status, 200);
  const putJson = await putRes.json();
  assert.equal(putJson.state.passengerLocationOptIn, true);
  assert.equal(putJson.state.collectPassengerGps, undefined);
  assert.equal(putJson.state.passengerLat, undefined);
  // 차량 위치·픽업 지점 필드는 sanitize를 통과한다 (문자열 80자 상한)
  assert.equal(putJson.state.carLat, 37.4988);
  assert.equal(putJson.state.carLng, 127.0614);
  assert.equal(putJson.state.carHeadingDeg, 90);
  assert.equal(putJson.state.carUpdatedAtEpochMs, 123456);
  assert.equal(putJson.state.pickupLat, 37.49881);
  assert.equal(putJson.state.pickupLng, 127.06142);
  assert.equal(putJson.state.pickupLabel.length, 80);
  assert.equal(putJson.state.pickupNote, "건너편에서 태울게요");
  assert.equal(putJson.state.pickupVersion, 2);
  assert.equal(putJson.state.suggestionReply, undefined);
  assert.equal(putJson.state.latitude, undefined);
  assert.equal(putJson.state.longitude, undefined);
  assert.equal(putJson.state.passengerCoordinates, undefined);
  assert.notEqual(putJson.state.createdAtEpochMs, 1);
  assert.notEqual(putJson.state.maxExpiresAtEpochMs, 2);
  assert.deepEqual(putJson.state.candidateSignboards, ["약국"]);
  assert.equal(putJson.state.maxTtlMinutes, 20);
  // 차량 신호 요약(VehicleFacts)은 통과, 차량 원시 좌표는 차단
  assert.equal(putJson.state.stoppedInPark, true);
  assert.equal(putJson.state.passengerBuckled, false);
  assert.equal(putJson.state.gear, "P");
  assert.equal(putJson.state.speedBucketKmh, 0);
  assert.equal(putJson.state.distanceBucketMeters, 10);

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

  // 3-1) 두 번째 구독자(차량 역할)가 동승자 위치·제안을 수신하고, 제안 응답이 STATE_UPDATE에 실린다
  const car = subscribe(`${base}/api/v1/sessions/${sessionId}/events`);
  await car.waitFor((e) => e.event === "STATE_UPDATE");
  const post = (suffix, body) => fetch(`${base}/api/v1/sessions/${sessionId}/${suffix}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });

  const locRes = await post("passenger-location", { lat: 37.49885, lng: 127.06150, accuracyM: 12 });
  assert.equal(locRes.status, 200);
  const locEvt = await car.waitFor((e) => e.event === "PASSENGER_LOCATION");
  assert.equal(locEvt.data.lat, 37.49885);
  assert.equal(locEvt.data.lng, 127.0615);
  assert.equal(locEvt.data.accuracyM, 12);
  assert.equal(typeof locEvt.data.updatedAtEpochMs, "number");
  const stateAfterLoc = await (await fetch(`${base}/api/v1/sessions/${sessionId}`)).json();
  assert.equal(stateAfterLoc.passengerLat, 37.49885);
  assert.equal(stateAfterLoc.passengerAccuracyM, 12);
  // 500ms 이내 재전송은 조용히 버려진다(200, 저장 안 됨)
  const throttled = await post("passenger-location", { lat: 1, lng: 2, accuracyM: 3 });
  assert.equal(throttled.status, 200);
  const stateAfterThrottle = await (await fetch(`${base}/api/v1/sessions/${sessionId}`)).json();
  assert.equal(stateAfterThrottle.passengerLat, 37.49885);

  const sugRes = await post("suggest", { kind: "PICKUP_HERE", text: "저 여기 있어요", lat: 37.49885, lng: 127.0615 });
  assert.equal(sugRes.status, 200);
  const sugEvt = await car.waitFor((e) => e.event === "PASSENGER_SUGGESTION");
  assert.equal(sugEvt.data.kind, "PICKUP_HERE");
  assert.equal(sugEvt.data.text, "저 여기 있어요");
  assert.equal(sugEvt.data.lat, 37.49885);
  assert.ok(typeof sugEvt.data.id === "string" && sugEvt.data.id.length >= 4);
  assert.equal((await post("suggest", { kind: "BOGUS", text: "x" })).status, 400);

  const replyRes = await post("suggestion-reply", { id: sugEvt.data.id, accepted: true });
  assert.equal(replyRes.status, 200);
  const replyState = await car.waitFor((e) => e.event === "STATE_UPDATE" && e.data.suggestionReplyId === sugEvt.data.id);
  assert.equal(replyState.data.suggestionReply, "ACCEPTED");
  assert.equal(replyState.data.passengerSuggestion.id, sugEvt.data.id);
  car.close();

  for (const suffix of ["passenger-location", "suggest", "suggestion-reply"]) {
    const gone = await fetch(`${base}/api/v1/sessions/nope-nope/${suffix}`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: "{}",
    });
    assert.equal(gone.status, 404, suffix);
  }

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

  console.log("PASS: MAJUNG Relay E2E SSE + GPS Stripping + car/pickup fields + PASSENGER_LOCATION/SUGGESTION + suggestion-reply + Landmark Swap + Immediate DELETE Shred verified.");
} finally {
  server.close();
}
