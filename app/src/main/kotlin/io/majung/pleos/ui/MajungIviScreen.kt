package io.majung.pleos.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.model.CannotStopOption
import io.majung.pleos.model.PickupPhase
import io.majung.pleos.model.SeatZone
import io.majung.pleos.orchestrator.ActiveSessionUiState
import io.majung.pleos.orchestrator.CoordinatorTrace
import io.majung.pleos.ui.map.MajungMap
import io.majung.pleos.ui.map.MapTheme
import io.majung.pleos.vehicle.VehicleSignalState
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** 운전자가 세션 시작 전에만 손대는 값. 주행 제한 중에는 입력란이 숨는다. */
data class IviInputs(
    val passengerName: String,
    val destinationKeyword: String,
    val plateLast4: String,
    val relayUrl: String
)

/** sim 플레이버에서만 쓰는 시나리오 재생기 (Pleos 실연동 빌드에서는 null). */
data class SimControls(
    val onDrive: () -> Unit,
    val onBoarding: () -> Unit,
    val onWrongDoor: () -> Unit,
    /** 중계 서버 없이도 동승자 제안 카드와 고른 자리 표시를 볼 수 있게, 동승자 위치와 "여기서 탈게요"를 기기 안에서 주입한다. */
    val onPassengerSuggest: () -> Unit
)

/**
 * 마중 IVI 화면 — "지도 하나, 할 일 하나".
 *
 * 화면은 지도가 전부다. 그 위에 상태 판 하나(동승자에게 가는 중, 거리), 연결 칩 하나, 아래에 지금 누를 버튼만 둔다.
 * 가는 동안 운전자가 누를 것은 [여기 못 서요] 하나, 멈춘 뒤에는 [주변 확인 끝, 문 열기] 하나다.
 * 차량 신호 패널·수동 지정·시뮬레이션·로그는 메인 화면에서 뺐다(연결 칩을 누르면 진단, sim 빌드는 시뮬레이션 대화상자).
 * 좌표계: Pleos Connect 2560dp 폭 기준으로 그리며, MainActivity가 더 좁은 화면에서 비례 축소한다.
 */
@Composable
fun MajungIviScreen(
    runtimeBadge: String,
    isDrivingRestricted: Boolean,
    session: ActiveSessionUiState,
    signals: VehicleSignalState,
    trace: CoordinatorTrace,
    inputs: IviInputs,
    onInputsChange: (IviInputs) -> Unit,
    passengerReadyNote: String?,
    actionNotice: String?,
    sdkCallbackLog: String,
    onStartSession: () -> Unit,
    onSurroundingsCheckedUnlock: () -> Unit,
    onCannotStop: (CannotStopOption) -> Unit,
    onResolveSuggestion: (Boolean) -> Unit,
    onEndSession: () -> Unit,
    simControls: SimControls?
) {
    val night = signals.lowBeamOn || isSystemInDarkTheme()
    val t = if (night) MajungTokens.Night else MajungTokens.Day
    val engine = remember { CurbsideCarFinderEngine() }
    var sheetOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var diagOpen by remember { mutableStateOf(false) }
    var simOpen by remember { mutableStateOf(false) }
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) { delay(1_000); value = System.currentTimeMillis() }
    }

    val name = inputs.passengerName.ifBlank { "동승자" }
    val target = session.currentStop?.targetSeat ?: SeatZone.ROW_1_RIGHT
    val trafficDoorOpen = signals.isDoorOpen(SeatZone.ROW_2_LEFT)
    val active = session.isSessionActive
    val atCurb = session.phase == PickupPhase.STEP4_CURBSIDE_STOPPED || session.phase == PickupPhase.STEP5_BOARDING_WELCOME
    val stopped = signals.stoppedInPark && atCurb
    val live = session.liveMap
    val distanceM: Int? = run {
        val car = live.car
        val pick = live.pickup
        if (car != null && pick != null) engine.distanceMeters(car.lat, car.lng, pick.lat, pick.lng) else session.vehicleFacts.distanceBucketMeters
    }
    val passengerAge = live.passengerUpdatedAtMs?.let { ((nowMs - it) / 1000).coerceAtLeast(0) }
    if (!active && sheetOpen) sheetOpen = false

    Box(modifier = Modifier.fillMaxSize().background(t.paper)) {
        MajungMap(state = live, theme = MapTheme(dark = night, palette = t.mapPalette), modifier = Modifier.fillMaxSize())

        // 상단 왼쪽: 상태 판 (세션 전에는 시작 판)
        if (trafficDoorOpen) {
            WarnBand(t, "차도 쪽 문이 열렸습니다", "후방 이륜차·차량 접근 위험. 인도 쪽 오른쪽 문을 이용해 주세요.")
        } else if (active) {
            val copy = statusCopy(session, signals, trace, name, distanceM, passengerAge, stopped, target)
            StatusPlate(t, copy, modifier = Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 64.dp))
        } else {
            StartPlate(t, session, inputs, onInputsChange, isDrivingRestricted, actionNotice, onStartSession, modifier = Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 64.dp))
        }

        // 상단 오른쪽: 연결 칩 (누르면 진단)
        val chipText = when {
            !active -> runtimeBadge.trim('[', ']')
            passengerAge == null -> "동승자 위치 기다리는 중"
            else -> "동승자 연결됨"
        }
        LiveChip(t, chipText, liveDot = active && passengerAge != null, onClick = { diagOpen = true }, modifier = Modifier.align(Alignment.TopEnd).padding(end = 64.dp, top = 64.dp))

        // 오른쪽 아래: 동승자 제안 카드
        session.pendingSuggestion?.let { s ->
            val sub = s.point?.let { p ->
                live.pickup?.let { pk -> "승차 지점에서 ${engine.distanceMeters(pk.lat, pk.lng, p.lat, p.lng)} m" }
            } ?: s.minutes?.let { "${it}분 뒤" }
            SuggestionCard(t, "${name}님의 제안", s.text, sub, onAccept = { onResolveSuggestion(true) }, onLater = { onResolveSuggestion(false) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 232.dp))
        }

        // 아래: 지금 누를 버튼만
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 64.dp, vertical = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                !active -> {
                    PlateButton(t, "설정", Tone.QUIET, onClick = { settingsOpen = true })
                    if (simControls != null) PlateButton(t, "시뮬레이션", Tone.QUIET, onClick = { simOpen = true })
                }
                trace.driverUnlocked -> {
                    PlateButton(t, "탑승 확인, 종료", Tone.PRIMARY, onClick = onEndSession)
                    Spacer(Modifier.weight(1f))
                    if (simControls != null) PlateButton(t, "시뮬레이션", Tone.QUIET, onClick = { simOpen = true })
                }
                stopped -> {
                    PlateButton(t, "주변 확인 끝, 문 열기", Tone.PRIMARY, onClick = onSurroundingsCheckedUnlock)
                    PlateButton(t, "여기 못 서요", Tone.SECONDARY, onClick = { sheetOpen = true })
                    Spacer(Modifier.weight(1f))
                    if (simControls != null) PlateButton(t, "시뮬레이션", Tone.QUIET, onClick = { simOpen = true })
                    PlateButton(t, "마중 종료", Tone.QUIET, onClick = onEndSession)
                }
                else -> {
                    PlateButton(t, "여기 못 서요", Tone.SECONDARY, onClick = { sheetOpen = true })
                    Spacer(Modifier.weight(1f))
                    if (simControls != null) PlateButton(t, "시뮬레이션", Tone.QUIET, onClick = { simOpen = true })
                    PlateButton(t, "마중 종료", Tone.QUIET, onClick = onEndSession)
                }
            }
        }

        if (sheetOpen) {
            CannotStopSheet(t, onPick = { option -> sheetOpen = false; onCannotStop(option) }, onCancel = { sheetOpen = false },
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(64.dp))
        }

        Text("© OpenStreetMap contributors", color = t.muted, fontSize = 18.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }

    if (settingsOpen) {
        PlateDialog(t, "설정", onDismiss = { settingsOpen = false }) {
            Text("동승자 웹 중계 서버", color = t.muted, fontSize = 28.sp)
            PlateField(t, inputs.relayUrl, { onInputsChange(inputs.copy(relayUrl = it)) }, "http://10.0.2.2:8080", Modifier.fillMaxWidth())
            Text("에뮬레이터에서 호스트 PC의 relay-server(8080)로 바로 연결되는 기본값입니다.", color = t.muted, fontSize = 24.sp)
        }
    }
    if (diagOpen) {
        PlateDialog(t, "진단", onDismiss = { diagOpen = false }) {
            DiagLine(t, "자동 판단", trace.line)
            DiagLine(t, "SDK/중계", sdkCallbackLog)
            DiagLine(t, "공유 링크", session.activeTicket?.shareUrl ?: "세션 시작 시 생성 (최대 20분, 탑승 확인 즉시 파기)")
            session.activeTicket?.shareUrl?.let { url ->
                // 시연·심사: 동승자 폰으로 QR을 찍으면 같은 링크가 열린다 (폰이 닿는 중계 서버 주소를 설정에 넣어 둘 것)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    QrImage(url, 280)
                    Text("동승자 폰으로 이 QR을 찍으면 같은 링크가 열립니다. 폰에서 닿는 중계 서버 주소(공개 HTTPS 또는 같은 Wi-Fi의 PC 주소)를 설정에 넣어 두세요.", color = t.muted, fontSize = 24.sp, modifier = Modifier.widthIn(max = 900.dp))
                }
            }
            DiagLine(t, "차량 신호", "기어 ${signals.gear.name}, ${signals.speedKmh.toInt()} km/h (${signals.speedSource.name.lowercase()}), 방향지시등 ${signals.turnSignal.name}, 하향등 ${if (signals.lowBeamOn) "ON" else "OFF"}, 와이퍼 ${if (signals.wiperOn) "ON" else "OFF"}")
            DiagLine(t, "탑승 좌석", "문 ${if (signals.isDoorOpen(target)) "열림" else "닫힘"}, 착좌 ${if (signals.isSeated(target)) "감지" else "없음"}, 벨트 ${if (signals.isBuckled(target)) "체결" else "미체결"}, 차도 쪽 문 ${if (trafficDoorOpen) "열림" else "닫힘"}")
            DiagLine(t, "지도", "차 ${live.car?.let { "%.5f, %.5f".format(it.lat, it.lng) } ?: "없음"}, 동승자 ${live.passenger?.let { "%.5f, %.5f (±${live.passengerAccuracyM?.toInt() ?: 0} m)".format(it.lat, it.lng) } ?: "없음"}, 승차 지점 ${live.pickupLabel.ifBlank { "없음" }} v${live.pickupVersion}")
            if (trace.cabinNote != null) DiagLine(t, "공조", trace.cabinNote)
            if (passengerReadyNote != null) DiagLine(t, "동승자", passengerReadyNote)
            DiagLine(t, "이벤트", "${signals.eventCount}건, ${signals.lastEventLog}")
        }
    }
    if (simOpen && simControls != null) {
        PlateDialog(t, "시뮬레이션 (sim 빌드 전용)", onDismiss = { simOpen = false }) {
            PlateButton(t, "주행 후 간판 앞 P단 정차", Tone.SECONDARY, enabled = active, onClick = { simOpen = false; simControls.onDrive() }, modifier = Modifier.fillMaxWidth())
            PlateButton(t, "동승자 제안: 여기서 탈게요 (서버 없이)", Tone.SECONDARY, enabled = active && session.pendingSuggestion == null && session.phase != PickupPhase.STEP5_BOARDING_WELCOME, onClick = { simOpen = false; simControls.onPassengerSuggest() }, modifier = Modifier.fillMaxWidth())
            PlateButton(t, "동승자 탑승 (문, 착좌, 벨트)", Tone.SECONDARY, enabled = session.phase == PickupPhase.STEP5_BOARDING_WELCOME, onClick = { simOpen = false; simControls.onBoarding() }, modifier = Modifier.fillMaxWidth())
            PlateButton(t, "차도 쪽 문 잘못 열림", Tone.SECONDARY, enabled = active, onClick = { simOpen = false; simControls.onWrongDoor() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---- 상태 문구 -----------------------------------------------------------------------------------

private data class StatusCopy(val head: String, val figure: String?, val unit: String?, val meta: String)

private fun statusCopy(
    session: ActiveSessionUiState,
    signals: VehicleSignalState,
    trace: CoordinatorTrace,
    name: String,
    distanceM: Int?,
    passengerAge: Long?,
    stopped: Boolean,
    target: SeatZone
): StatusCopy {
    val figure = distanceM?.toString()
    val eta = ceil(session.remainingEtaSeconds / 60.0).toInt().coerceAtLeast(1)
    val passengerLine = passengerAge?.let { "동승자 위치 ${it}초 전" } ?: "동승자 위치 아직 없음"
    val note = session.liveMap.pickupNote
    return when {
        session.phase == PickupPhase.STEP5_BOARDING_WELCOME && signals.isSeated(target) ->
            StatusCopy("${name}님, 반갑습니다", null, null, "문 닫힘과 안전벨트가 확인되면 공유가 자동으로 끝납니다")
        trace.driverUnlocked ->
            StatusCopy("문 열림. ${name}님을 기다립니다", figure, "m", "조수석 잠금 해제, 창문 ${trace.windowPercent}%. 인도 쪽 오른쪽 문으로 탑니다")
        stopped ->
            StatusCopy("P단 정차. 주변을 확인하세요", figure, "m", note ?: "동승자는 인도 쪽, 오른쪽 문으로 탑니다")
        session.phase == PickupPhase.STEP4_CURBSIDE_STOPPED && !signals.stoppedInPark ->
            StatusCopy("정차 후 P단으로 바꿔 주세요", figure, "m", "P단이 확인되어야 동승자에게 '지금 타도 돼요'가 갑니다")
        else ->
            StatusCopy("${name}님에게 가는 중", figure, "m", note ?: (if (figure != null) "약 ${eta}분, $passengerLine" else passengerLine))
    }
}

// ---- 판과 버튼 (Pleos 런처의 결: 작은 라운드, 얇은 선, 검은 판, 그림자 없음) ------------------------

private enum class Tone { PRIMARY, SECONDARY, QUIET }

private val Shape get() = RoundedCornerShape(MajungTokens.Radius)

@Composable
private fun StatusPlate(t: MajungPalette, copy: StatusCopy, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(min = 760.dp, max = 1100.dp)
            .background(t.plate, Shape)
            .padding(horizontal = 52.dp, vertical = 44.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(copy.head, color = t.plateInk, fontSize = 64.sp, fontWeight = FontWeight.SemiBold, lineHeight = 76.sp, letterSpacing = (-0.5).sp, maxLines = 2)
        if (copy.figure != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(copy.figure, color = t.plateInk, fontSize = 124.sp, fontWeight = FontWeight.Light, lineHeight = 124.sp, letterSpacing = (-2).sp)
                if (copy.unit != null) Text(copy.unit, color = t.plateMuted, fontSize = 48.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(bottom = 18.dp))
            }
        }
        Text(copy.meta, color = t.plateMuted, fontSize = 34.sp, lineHeight = 44.sp, maxLines = 2)
    }
}

@Composable
private fun WarnBand(t: MajungPalette, head: String, sub: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 64.dp, vertical = 64.dp).background(t.stop, Shape).padding(horizontal = 56.dp, vertical = 44.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(head, color = t.stopInk, fontSize = 64.sp, fontWeight = FontWeight.SemiBold, lineHeight = 76.sp)
        Text(sub, color = t.stopInk, fontSize = 34.sp, lineHeight = 44.sp)
    }
}

@Composable
private fun StartPlate(
    t: MajungPalette,
    session: ActiveSessionUiState,
    inputs: IviInputs,
    onInputsChange: (IviInputs) -> Unit,
    isDrivingRestricted: Boolean,
    actionNotice: String?,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val done = session.phase == PickupPhase.STEP6_COMPLETED_SHREDDED
    Column(
        modifier = modifier
            .width(1180.dp)
            .background(t.paper3, Shape)
            .border(MajungTokens.Hairline, t.rule, Shape)
            .padding(horizontal = 52.dp, vertical = 44.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(if (done) "탑승 완료. 공유를 끝냈습니다" else "누구를 마중하나요?", color = t.ink, fontSize = 64.sp, fontWeight = FontWeight.SemiBold, lineHeight = 76.sp, letterSpacing = (-0.5).sp)
        // 두 문구 모두 두 줄 높이를 차지하게 해, 세션 종료 뒤에도 시작 버튼의 위치가 같다 (운전자가 보지 않고 누르는 자리).
        Text(
            if (done) "링크와 위치 정보가 서버에서 삭제되었습니다. 다음 마중을 같은 자리에서 시작할 수 있습니다. 동승자 호칭과 장소는 그대로 둡니다." else "동승자에게 일회성 지도 링크를 보냅니다. 링크는 탑승 확인 즉시 사라지고, 늦어도 20분 뒤 만료됩니다.",
            color = t.muted, fontSize = 32.sp, lineHeight = 42.sp, minLines = 2, maxLines = 2
        )
        if (!isDrivingRestricted) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                PlateField(t, inputs.passengerName, { onInputsChange(inputs.copy(passengerName = it)) }, "동승자 호칭", Modifier.weight(0.8f))
                PlateField(t, inputs.destinationKeyword, { onInputsChange(inputs.copy(destinationKeyword = it)) }, "픽업 장소, 간판", Modifier.weight(1.4f))
                PlateField(t, inputs.plateLast4, { onInputsChange(inputs.copy(plateLast4 = it.take(4))) }, "번호판 끝 4자리", Modifier.weight(0.8f))
            }
        } else {
            Text("주행 중에는 저장된 값으로 시작합니다. ${inputs.passengerName.ifBlank { "동승자" }}, ${inputs.destinationKeyword}, 번호판 끝 ${inputs.plateLast4.ifBlank { "----" }}", color = t.ink2, fontSize = 32.sp)
        }
        if (actionNotice != null) Text(actionNotice, color = t.stop, fontSize = 30.sp)
        PlateButton(t, if (done) "새 마중 시작, 링크 보내기" else "픽업 시작, 링크 보내기", Tone.PRIMARY, onClick = onStart, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LiveChip(t: MajungPalette, text: String, liveDot: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(t.paper3)
            .border(MajungTokens.Hairline, t.rule, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 36.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(modifier = Modifier.size(22.dp).background(if (liveDot) t.accent else t.rule, CircleShape))
        Text(text, color = t.ink2, fontSize = 32.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SuggestionCard(t: MajungPalette, who: String, say: String, sub: String?, onAccept: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(920.dp)
            .background(t.paper3, Shape)
            .border(MajungTokens.Hairline, t.rule, Shape)
            .padding(horizontal = 52.dp, vertical = 44.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(who, color = t.muted, fontSize = 32.sp, fontWeight = FontWeight.Medium)
        Text(say, color = t.ink, fontSize = 56.sp, fontWeight = FontWeight.SemiBold, lineHeight = 66.sp, letterSpacing = (-0.5).sp, maxLines = 2)
        if (sub != null) Text(sub, color = t.muted, fontSize = 34.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PlateButton(t, "수락", Tone.PRIMARY, onClick = onAccept, modifier = Modifier.weight(1f))
            PlateButton(t, "나중에", Tone.QUIET, onClick = onLater, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CannotStopSheet(t: MajungPalette, onPick: (CannotStopOption) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(t.paper3, Shape).border(MajungTokens.Hairline, t.rule, Shape).padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("어디서 태울까요? 동승자에게 바로 알립니다.", color = t.muted, fontSize = 38.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            OptionPlate(t, "건너편에서", "U턴, 약 1분", primary = true, onClick = { onPick(CannotStopOption.OTHER_SIDE_UTURN) }, modifier = Modifier.weight(1f))
            OptionPlate(t, "앞으로 30 m", "같은 쪽, 약 20초", primary = false, onClick = { onPick(CannotStopOption.AHEAD_30M) }, modifier = Modifier.weight(1f))
            OptionPlate(t, "한 바퀴 돌고 올게요", "약 3분, 같은 자리", primary = false, onClick = { onPick(CannotStopOption.LOOP_AROUND) }, modifier = Modifier.weight(1f))
            PlateButton(t, "취소", Tone.QUIET, onClick = onCancel)
        }
    }
}

@Composable
private fun OptionPlate(t: MajungPalette, title: String, sub: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (primary) t.plate else t.paper2
    val fg = if (primary) t.plateInk else t.ink
    Column(
        modifier = modifier
            .heightIn(min = 250.dp)
            .clip(Shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 44.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)
    ) {
        Text(title, color = fg, fontSize = 56.sp, fontWeight = FontWeight.SemiBold, lineHeight = 66.sp, letterSpacing = (-0.5).sp)
        Text(sub, color = if (primary) t.plateMuted else t.muted, fontSize = 34.sp)
    }
}

@Composable
private fun PlateButton(t: MajungPalette, label: String, tone: Tone, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val (bg, fg, edge) = when (tone) {
        Tone.PRIMARY -> Triple(t.plate, t.plateInk, t.plate)
        Tone.SECONDARY -> Triple(t.paper3, t.ink, t.ink)
        Tone.QUIET -> Triple(t.paper3, t.ink2, t.rule)
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Shape,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg, disabledContainerColor = t.paper2, disabledContentColor = t.muted),
        modifier = modifier.heightIn(min = 124.dp).border(MajungTokens.Hairline, if (enabled) edge else t.rule, Shape),
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp)
    ) {
        Text(label, fontSize = 42.sp, fontWeight = if (tone == Tone.QUIET) FontWeight.Medium else FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PlateField(t: MajungPalette, value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = t.muted, fontSize = 28.sp) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 38.sp, color = t.ink),
        shape = Shape,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = t.ink, unfocusedTextColor = t.ink,
            focusedBorderColor = t.accent, unfocusedBorderColor = t.rule,
            cursorColor = t.accent, focusedContainerColor = t.paper3, unfocusedContainerColor = t.paper3
        )
    )
}

/** 공유 링크 QR. 흰 바탕 위 먹색 모듈, 여백 1모듈. 링크가 바뀔 때만 다시 만든다. */
@Composable
private fun QrImage(text: String, sizeDp: Int) {
    val bitmap = remember(text) { qrBitmap(text, 512) }
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "동승자 링크 QR", modifier = Modifier.size(sizeDp.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp))
}

private fun qrBitmap(text: String, px: Int): android.graphics.Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 1))
    val dark = 0xFF111417.toInt(); val light = 0xFFFFFFFF.toInt()
    val pixels = IntArray(px * px) { i -> if (matrix.get(i % px, i / px)) dark else light }
    return android.graphics.Bitmap.createBitmap(pixels, px, px, android.graphics.Bitmap.Config.ARGB_8888)
}

@Composable
private fun PlateDialog(t: MajungPalette, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.width(1400.dp).background(t.paper3, Shape).border(MajungTokens.Hairline, t.rule, Shape).padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = t.ink, fontSize = 50.sp, fontWeight = FontWeight.SemiBold)
                PlateButton(t, "닫기", Tone.QUIET, onClick = onDismiss)
            }
            content()
        }
    }
}

@Composable
private fun DiagLine(t: MajungPalette, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Text(label, color = t.muted, fontSize = 28.sp, modifier = Modifier.width(200.dp))
        Text(value, color = t.ink, fontSize = 28.sp, lineHeight = 38.sp, modifier = Modifier.weight(1f))
    }
}

@Suppress("unused")
private val unusedColorGuard: Color = Color.Unspecified
