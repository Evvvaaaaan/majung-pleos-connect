package io.majung.pleos.ui

import androidx.compose.ui.graphics.Color

/**
 * 마중 IVI 디자인 토큰 — 현대차 Pleos(ccNC) 다크 IVI 언어를 따른다.
 * - 검정에 가까운 바탕, 넓은 여백, 큰 타이포, 한 화면 한 메시지
 * - 포인트 색은 현대 브랜드 블루 계열(Hyundai Blue #002C5F / Active Blue #00AAD2), 환대 카드는 Sand(#E4DCD3)
 * - 상표 자산(로고·전용 서체)은 사용하지 않고 색·위계·간격만 따른다.
 * - 모든 터치 타깃 ≥ 56dp, 주행 중(UX 제한) 입력란 숨김
 */
object MajungTokens {
    val Canvas = Color(0xFF0B0F14)
    val Surface = Color(0xFF151B23)
    val SurfaceRaised = Color(0xFF1C2430)
    val Hairline = Color(0xFF2A333F)
    val Ink = Color(0xFFF5F7FA)
    val Muted = Color(0xFF9AA4B2)
    val HyundaiBlue = Color(0xFF002C5F)
    val ActiveBlue = Color(0xFF00AAD2)
    val Sand = Color(0xFFE4DCD3)
    val SandInk = Color(0xFF1B1B1B)
    val Danger = Color(0xFFE63312)
    val Success = Color(0xFF2FBF71)
    val Warning = Color(0xFFF2A900)
}
