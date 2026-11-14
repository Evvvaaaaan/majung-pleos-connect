package io.majung.pleos.relay

import io.majung.pleos.model.GeoPoint
import io.majung.pleos.model.LiveMapState
import io.majung.pleos.model.PassengerSuggestion
import io.majung.pleos.model.PickupPhase
import io.majung.pleos.model.PickupSessionShareTicket
import io.majung.pleos.model.SafePitstopStop
import io.majung.pleos.model.SuggestionKind
import io.majung.pleos.model.VehicleFacts
import io.majung.pleos.orchestrator.RelayNotificationGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 차량 IVI <-> 무설치 동승자 안심 웹 실시간 SSE/REST 중계 클라이언트
 * - 동승자 위치는 브라우저 권한 동의(opt-in) 시에만 서버 메모리에 세션 동안 보관 (디스크·로그 기록 없음)
 * - 탑승 완료 또는 운전자 종료 시 DELETE 호출로 서버 메모리에서 즉시 파기 (미종료 시 최대 20분 자동 만료)
 */
class EphemeralRelayClient(
    @Volatile var baseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
) : RelayNotificationGateway {

    private var activeEventSource: EventSource? = null

    /**
     * 차량 키: 이 앱 프로세스가 만든 세션을 이 프로세스만 고칠 수 있게 하는 비밀. 세션을 만드는 첫 PUT 에 실리고,
     * 이후 모든 차량 쪽 호출(PUT·DELETE·suggestion-reply·events)에 헤더로 붙는다. 동승자에게는 전달되지 않는다.
     */
    private val carKey: String = java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    private fun Request.Builder.asCar(): Request.Builder = header(CAR_KEY_HEADER, carKey)

    override suspend fun publishSessionState(
        ticket: PickupSessionShareTicket,
        stop: SafePitstopStop,
        remainingSeconds: Int,
        phase: PickupPhase,
        boardingDoorUnlocked: Boolean,
        welcomeGreetingActive: Boolean,
        facts: VehicleFacts,
        liveMap: LiveMapState
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val candidatesJson = JSONArray().apply {
                ticket.candidateSignboards.forEach { put(it) }
            }
            val payload = JSONObject().apply {
                put("sessionId", ticket.sessionId)
                put("passengerName", stop.passengerName)
                put("pitstopName", stop.pitstopName)
                put("landmarkSignboard", ticket.landmarkSignboard)
                put("candidateSignboards", candidatesJson)
                put("walkingMetersWithoutCrossing", stop.walkingMetersWithoutCrossing)
                put("vehicleModelLabel", ticket.vehicleModelLabel)
                put("plateLast4", ticket.plateLast4)
                if (ticket.offsetMeters != null) {
                    put("offsetMeters", ticket.offsetMeters)
                } else {
                    put("offsetMeters", JSONObject.NULL)
                }
                put("offsetGuideText", ticket.offsetGuideText)
                put("locationReliability", ticket.locationReliability.name)
                put("runtimeSourceBadge", ticket.runtimeSourceBadge)
                put("remainingSeconds", remainingSeconds)
                put("phase", phase.name)
                put("boardingDoorUnlocked", boardingDoorUnlocked)
                put("welcomeGreetingActive", welcomeGreetingActive)
                // 차량 신호로만 확인되는 사실 — 동승자 웹의 큰 상태 카드가 이 값으로만 바뀐다
                put("stoppedInPark", facts.stoppedInPark)
                put("trafficSideDoorOpen", facts.trafficSideDoorOpen)
                put("passengerDoorOpen", facts.passengerDoorOpen)
                put("passengerSeated", facts.passengerSeated)
                put("passengerBuckled", facts.passengerBuckled)
                put("gear", facts.gear.name)
                put("speedBucketKmh", facts.speedBucketKmh)
                if (facts.distanceBucketMeters != null) put("distanceBucketMeters", facts.distanceBucketMeters) else put("distanceBucketMeters", JSONObject.NULL)
                put("signalSource", facts.signalSource)
                put("issuedAtEpochMs", ticket.issuedAtEpochMs)
                put("maxExpiresAtEpochMs", ticket.maxExpiresAtEpochMs)
                liveMap.car?.let { put("carLat", it.lat); put("carLng", it.lng) }
                liveMap.carHeadingDeg?.let { put("carHeadingDeg", it) }
                liveMap.carUpdatedAtMs?.let { put("carUpdatedAtEpochMs", it) }
                liveMap.pickup?.let { put("pickupLat", it.lat); put("pickupLng", it.lng) }
                put("pickupLabel", liveMap.pickupLabel)
                liveMap.pickupNote?.let { put("pickupNote", it) }
                put("pickupVersion", liveMap.pickupVersion)
                // 동승자 토큰(공유 링크의 ?t=): 서버가 세션을 만들 때 한 번 받아 두고, 동승자 쪽 호출을 이 값으로 검사한다
                put("passengerToken", ticket.oneTimeToken)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/v1/sessions/${ticket.sessionId}")
                .asCar()
                .put(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun replySuggestion(sessionId: String, suggestionId: String, accepted: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().apply {
                    put("id", suggestionId)
                    put("accepted", accepted)
                }.toString()
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/v1/sessions/$sessionId/suggestion-reply")
                    .asCar()
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    override suspend fun sendKidsFallbackSms(stop: SafePitstopStop, smsBody: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().apply {
                    put("passengerName", stop.passengerName)
                    put("smsBody", smsBody)
                }.toString()
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/v1/sms-fallback")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    override suspend fun shredEphemeralSession(sessionId: String): Boolean {
        activeEventSource?.cancel()
        activeEventSource = null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/v1/sessions/$sessionId")
                    .asCar()
                    .delete()
                    .build()
                httpClient.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    fun subscribePassengerEvents(
        sessionId: String,
        onPassengerReady: (statusText: String) -> Unit,
        onLandmarkSwapRequested: (newLandmark: String) -> Unit,
        onPassengerLocation: (GeoPoint, accuracyM: Double?) -> Unit = { _, _ -> },
        onPassengerSuggestion: (PassengerSuggestion) -> Unit = {}
    ) {
        activeEventSource?.cancel()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/v1/sessions/$sessionId/events")
            .asCar()
            .build()

        activeEventSource = EventSources.createFactory(httpClient)
            .newEventSource(request, object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    when (type) {
                        "PASSENGER_READY" -> {
                            val note = runCatching {
                                JSONObject(data).optString("statusText", "기준 간판 앞 대기 중")
                            }.getOrDefault("기준 간판 앞 대기 중")
                            onPassengerReady(note)
                        }
                        "PASSENGER_LOCATION" -> runCatching {
                            val j = JSONObject(data)
                            onPassengerLocation(
                                GeoPoint(j.getDouble("lat"), j.getDouble("lng")),
                                if (j.isNull("accuracyM")) null else j.optDouble("accuracyM")
                            )
                        }
                        "PASSENGER_SUGGESTION" -> runCatching {
                            val j = JSONObject(data)
                            val hasPoint = !j.isNull("lat") && !j.isNull("lng")
                            onPassengerSuggestion(
                                PassengerSuggestion(
                                    id = j.getString("id"),
                                    kind = SuggestionKind.valueOf(j.getString("kind")),
                                    text = j.optString("text", ""),
                                    point = if (hasPoint) GeoPoint(j.getDouble("lat"), j.getDouble("lng")) else null,
                                    minutes = if (j.isNull("minutes")) null else j.optInt("minutes"),
                                    atMs = j.optLong("atEpochMs", System.currentTimeMillis())
                                )
                            )
                        }
                        "LANDMARK_SWAP_REQUEST" -> {
                            val nextLandmark = runCatching {
                                JSONObject(data).optString("landmarkSignboard", "")
                            }.getOrDefault("")
                            if (nextLandmark.isNotBlank()) {
                                onLandmarkSwapRequested(nextLandmark)
                            }
                        }
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    // 로컬 중계 서버 미실행 시에도 앱 UI가 중단되지 않도록 조용히 폴백 처리
                }
            })
    }

    fun close() {
        activeEventSource?.cancel()
        activeEventSource = null
    }

    companion object {
        const val CAR_KEY_HEADER = "X-Majung-Car-Key"
    }
}
