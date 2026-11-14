package io.majung.pleos.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.majung.pleos.ui.map.MapPalette

/**
 * 마중 IVI 디자인 토큰 — 방향 A "사인보드"를 Pleos Connect 런처의 결로 다듬은 것.
 *
 * - Pleos 런처(낮)에서 가져온 사실: 밝은 회색 바탕, 검은 판(검색판), 흰 판에 얇은 선, 작은 라운드, 시스템 한글 서체.
 *   서체는 따로 싣지 않고 차량에 탑재된 시스템 서체(FontFamily.Default)를 그대로 쓴다. 숫자도 같은 서체, 가는 굵기로 크게.
 * - 라운드는 한 단계(12dp), 선은 얇게(2dp), 그림자는 없다. 판은 바탕과 밝기 차이로만 뜬다.
 * - 코발트(accent)는 '지금 살아 있는 것'(내 차 마커, 연결 칩의 점, 기본 행동의 테두리)에만 쓴다. 호박색은 지도 위 승차 지점에만.
 * - 낮/밤 두 벌: 하향등(lowBeam) 또는 시스템 야간 모드에서 밤 토큰으로 바뀐다. 밤에는 검은 판이 흰 판으로 뒤집힌다.
 */
data class MajungPalette(
    val paper: Color, val paper2: Color, val paper3: Color,
    val ink: Color, val ink2: Color, val muted: Color, val rule: Color,
    val accent: Color, val accentInk: Color,
    val amber: Color,
    val go: Color, val goInk: Color,
    val stop: Color, val stopInk: Color,
    val plate: Color, val plateInk: Color, val plateMuted: Color
) {
    /** 지도 마커: 내 차는 호스트 내비의 현재 위치 화살표처럼 파란 원에 흰 화살표, 동승자는 먹색 점, 승차 지점은 호박색 판. */
    val mapPalette: MapPalette
        get() = MapPalette(
            car = accentInk.toArgb(), carRing = accent.toArgb(),
            passenger = ink.toArgb(), passengerRing = paper3.toArgb(),
            pickup = amber.toArgb(), pickupInk = Color(0xFF111417).toArgb(),
            route = accent.toArgb()
        )
}

object MajungTokens {
    val Radius = 12.dp
    val Hairline = 2.dp

    val Day = MajungPalette(
        paper = Color(0xFFF5F7FA), paper2 = Color(0xFFE9ECF1), paper3 = Color(0xFFFCFDFE),
        ink = Color(0xFF111417), ink2 = Color(0xFF2B3036), muted = Color(0xFF5F6670), rule = Color(0xFFD5D9DF),
        accent = Color(0xFF1F7BFF), accentInk = Color(0xFFFCFDFE),
        amber = Color(0xFFF4B522),
        go = Color(0xFF0F8A4B), goInk = Color(0xFFFCFDFE),
        stop = Color(0xFFD23B2E), stopInk = Color(0xFFFCFDFE),
        plate = Color(0xFF111417), plateInk = Color(0xFFF7F9FC), plateMuted = Color(0xFFB9BFC8)
    )
    val Night = MajungPalette(
        paper = Color(0xFF0E1116), paper2 = Color(0xFF1A1E26), paper3 = Color(0xFF13171E),
        ink = Color(0xFFF2F4F7), ink2 = Color(0xFFCDD2DA), muted = Color(0xFF9AA1AB), rule = Color(0xFF2F343D),
        accent = Color(0xFF4D96FF), accentInk = Color(0xFF0E1116),
        amber = Color(0xFFF4B522),
        go = Color(0xFF2DB46B), goInk = Color(0xFF0E1116),
        stop = Color(0xFFE85A4E), stopInk = Color(0xFF0E1116),
        plate = Color(0xFFF2F4F7), plateInk = Color(0xFF111417), plateMuted = Color(0xFF4A5058)
    )
}
