#!/usr/bin/env python3
"""
공식 접수양식(contest-application-consent.docx)의 02 '앱 프로젝트 소개 / 심사 자료' 입력란을 마중(MAJUNG) 내용으로 채운다.

- 01 참가신청서 · 03/04 동의·서명은 개인정보라 비워 둔다(참가자가 직접 기입·서명).
- 앱명 / 프로젝트명 공란만 "마중 (MAJUNG)"으로 채운다.
- 사용: python3 tools/submission/fill_official_form.py <official.docx> <out.docx> [--ivi img.png] [--web img.png]
"""
import re
import shutil
import struct
import sys
import tempfile
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
SZ_BODY = 19   # 9.5pt
SZ_SMALL = 17  # 8.5pt


# ------------------------------------------------------------------ XML helpers
def run(text, bold=False, size=SZ_BODY, color=None, italic=False):
    rpr = f'<w:rFonts w:hint="eastAsia"/>{"<w:b/>" if bold else ""}{"<w:i/>" if italic else ""}'
    if color:
        rpr += f'<w:color w:val="{color}"/>'
    rpr += f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/><w:lang w:eastAsia="ko-KR"/>'
    return f'<w:r><w:rPr>{rpr}</w:rPr><w:t xml:space="preserve">{escape(text)}</w:t></w:r>'


def para(runs, before=0, after=60, left=0, hanging=0, line=276, keep=False):
    """runs: 이미 XML로 만들어진 <w:r> 문자열(run()/rich()/drawing). 평문은 반드시 run()으로 감싸서 넘긴다."""
    ind =f'<w:ind w:left="{left}" w:hanging="{hanging}"/>' if (left or hanging) else ''
    return (f'<w:p><w:pPr>{"<w:keepNext/>" if keep else ""}<w:spacing w:before="{before}" w:after="{after}" w:line="{line}" w:lineRule="auto"/>'
            f'{ind}</w:pPr>{runs}</w:p>')


def heading(text):
    return para(run(text, bold=True, size=20, color="002C5F"), before=120, after=60, keep=True)


def body(text, **kw):
    """'**굵게**' 마크업을 지원하는 본문 문단"""
    return para(rich(text), **kw)


def rich(text, size=SZ_BODY):
    parts = re.split(r'(\*\*.+?\*\*)', text)
    out = []
    for p in parts:
        if not p:
            continue
        if p.startswith('**') and p.endswith('**'):
            out.append(run(p[2:-2], bold=True, size=size))
        else:
            out.append(run(p, size=size))
    return ''.join(out)


def bullet(text, level=0):
    left = 360 + level * 300
    return para(run('• ', size=SZ_BODY) + rich(text), after=40, left=left, hanging=240)


def note(text):
    return para(rich(text, size=SZ_SMALL), after=60)


def table(headers, rows, widths, header_fill="E4DCD3", font=SZ_SMALL):
    total = sum(widths)
    grid = ''.join(f'<w:gridCol w:w="{w}"/>' for w in widths)
    borders = ('<w:tblBorders>' + ''.join(f'<w:{s} w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/>' for s in
               ('top', 'left', 'bottom', 'right', 'insideH', 'insideV')) + '</w:tblBorders>')
    tblpr = (f'<w:tblPr><w:tblW w:w="{total}" w:type="dxa"/>{borders}<w:tblLayout w:type="fixed"/>'
             f'<w:tblCellMar><w:left w:w="80" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr>')

    def cell(text, w, fill=None, bold=False):
        tcpr = f'<w:tcPr><w:tcW w:w="{w}" w:type="dxa"/>' + (f'<w:shd w:val="clear" w:color="auto" w:fill="{fill}"/>' if fill else '') + '</w:tcPr>'
        ps = ''.join(para(rich(line, size=font) if not bold else run(line, bold=True, size=font), after=0, line=240) for line in text.split('\n'))
        return f'<w:tc>{tcpr}{ps}</w:tc>'

    hdr = '<w:tr><w:trPr><w:tblHeader/></w:trPr>' + ''.join(cell(h, w, header_fill, True) for h, w in zip(headers, widths)) + '</w:tr>'
    body_rows = ''.join('<w:tr>' + ''.join(cell(c, w) for c, w in zip(r, widths)) + '</w:tr>' for r in rows)
    return f'<w:tbl>{tblpr}<w:tblGrid>{grid}</w:tblGrid>{hdr}{body_rows}</w:tbl>' + para(run('', size=8), after=0, line=120)


def png_size(path):
    with open(path, 'rb') as f:
        head = f.read(24)
    assert head[:8] == b'\x89PNG\r\n\x1a\n', f'not a PNG: {path}'
    w, h = struct.unpack('>II', head[16:24])
    return w, h


class Images:
    """word/media 에 PNG를 추가하고 인라인 drawing XML을 만든다."""

    def __init__(self, unpacked: Path):
        self.unpacked = unpacked
        self.rels_path = unpacked / 'word' / '_rels' / 'document.xml.rels'
        self.rels = self.rels_path.read_text(encoding='utf-8')
        self.ct_path = unpacked / '[Content_Types].xml'
        self.ct = self.ct_path.read_text(encoding='utf-8')
        if 'Extension="png"' not in self.ct:
            self.ct = self.ct.replace('</Types>', '<Default Extension="png" ContentType="image/png"/></Types>')
        self.n = 0
        (unpacked / 'word' / 'media').mkdir(exist_ok=True)

    def add(self, path: Path, width_cm: float, caption: str):
        self.n += 1
        name = f'majung_{self.n}.png'
        shutil.copy(path, self.unpacked / 'word' / 'media' / name)
        rid = f'rIdMajungImg{self.n}'
        self.rels = self.rels.replace('</Relationships>',
                                      f'<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/{name}"/></Relationships>')
        w, h = png_size(path)
        cx = int(width_cm * 360000)
        cy = int(cx * h / w)
        drawing = (
            f'<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0" '
            f'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">'
            f'<wp:extent cx="{cx}" cy="{cy}"/><wp:docPr id="{900 + self.n}" name="{name}"/>'
            f'<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
            f'<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
            f'<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">'
            f'<pic:nvPicPr><pic:cNvPr id="{900 + self.n}" name="{name}"/><pic:cNvPicPr/></pic:nvPicPr>'
            f'<pic:blipFill><a:blip r:embed="{rid}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/>'
            f'<a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
            f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>'
            f'</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>')
        return para(drawing, after=0) + para(run(caption, size=SZ_SMALL, color="595959", italic=True), after=120)

    def save(self):
        self.rels_path.write_text(self.rels, encoding='utf-8')
        self.ct_path.write_text(self.ct, encoding='utf-8')


# ------------------------------------------------------------------ 내용
GITHUB = "https://github.com/Evvvaaaaan/majung-pleos-connect"

def content(images: Images, ivi_png: Path | None, web_png: Path | None):
    c = {}

    c['app_summary'] = (
        body("마중(MAJUNG)은 혼잡한 학원가·역 앞 픽업의 마지막 100m에서, 스마트폰 위치 공유가 알 수 없는 세 가지 — "
             "**차가 정말 P단에 멈췄는지, 동승자가 어느 문을 열었는지, 안전벨트를 맸는지** — 를 Pleos Vehicle SDK 신호로 확인해 "
             "동승자 웹에 \"지금 타도 돼요\"를 알리고, 벨트 체결과 동시에 위치 공유를 스스로 끝내는 Pleos Connect 차량용 안심 픽업 앱입니다.")
    )

    c['planning_background'] = (
        body("내비게이션이 \"목적지에 도착했습니다\"라고 말한 뒤부터 아이가 차 문을 열기까지가 학부모에게 가장 혼란스러운 구간입니다. "
             "마중은 이 **마지막 100m**에 집중합니다.")
        + bullet("**같은 색 SUV 행렬 속 내 차 찾기:** 야간·우천의 학원가와 역 출구 앞에는 비슷한 차가 연석을 따라 줄지어 섭니다. 앞차 때문에 약속한 간판보다 두세 대 앞이나 뒤에 서면, 아이는 차도 쪽으로 내려오거나 반대 방향으로 걷습니다.")
        + bullet("**주행 중 반복 통화:** 정차할 곳을 찾으면서 \"어디야?\", \"무슨 간판 앞이야?\" 전화를 받는 것은 Pleos 주행 안전 설계 가이드가 경계하는 시각·인지 분산입니다. 어린이보호구역(도로교통법 제32조 제8호)에서는 정차 자체가 제한되어 통화는 더 길어집니다.")
        + bullet("**폰이 못 푸는 부분:** 위치 공유·도착 예정 시간·번호판 표시는 택시 호출 앱이 이미 합니다. 그러나 폰은 **차가 멈췄는지(기어 P), 어느 문이 열렸는지(차도 쪽 vs 인도 쪽), 벨트를 맸는지**를 모릅니다. 이 세 가지는 차량 신호에서만 나옵니다.")
        + bullet("**규모:** 2024년 초·중·고 사교육비 조사(교육부·통계청) 기준 초등학생 약 250만 명 중 87.7%가 사교육에 참여하고, 18세 미만 자녀가 있는 가구의 58.5%(2024 하반기, 통계청 맞벌이 가구 고용현황)가 맞벌이입니다. 저녁 학원 픽업은 매일 반복되는, 가장 흔한 가족 주행 상황입니다.")
        + note("※ 수치 출처: 교육부·통계청 「2024년 초중고 사교육비 조사」(2025.3.13), 통계청 「2024년 하반기 지역별고용조사 맞벌이 가구 및 1인 가구 취업 현황」(2025.6.19).")
    )

    c['vehicle_scenario'] = (
        heading("가. 차만 아는 3가지 — 핵심 기능 (구현·테스트 완료)")
        + table(["폰은 모른다", "차는 안다 (Vehicle SDK 신호)", "마중이 하는 일"],
                [["차가 정말 멈췄는지", "Gear.registerGearSelection = PARK\n+ 차속 0 (AAOS PERF_VEHICLE_SPEED)", "그때서야 동승자 웹 상태 카드가 \"지금 타도 돼요\"로 바뀜. 차속 0이어도 D단이면 바뀌지 않음(테스트로 보장)"],
                 ["동승자가 어느 문을 열었는지", "Door.registerDoorPosition(ROW_2_LEFT) 열림", "운전 부하 게이트를 우회하는 CRITICAL 실내 음성 \"차도 방향 왼쪽 문이 열렸습니다. 인도 쪽 오른쪽 문을 이용해 주세요.\""],
                 ["안전벨트를 맸는지", "Seat.registerSeatBeltBuckledState + 도어 닫힘", "일회성 공유 링크를 서버에서 즉시 삭제(DELETE), 창문·독서등·공조를 세션 전 상태로 원복"]],
                [2300, 3400, 4000])
        + heading("나. 6단계 흐름 — 트리거는 모두 차량 신호, 운전자가 누르는 버튼은 하나")
        + table(["단계", "트리거 (차량 신호)", "차량 → 동승자 웹 / 차량 제어", "SDK · API"],
                [["1 합의", "운전자 \"픽업 시작\" 또는 Gleo 음성 START_SAFE_PICKUP", "기준 간판·차종·번호판 끝 4자리·일회성 웹 링크(최대 20분)", "NaviHelper.requestRoute(WithWaypoints), CarInfo.getVINInfo"],
                 ["2 접근", "반경 80m + ≤25km/h + 우측 방향지시등 (또는 50m 저속 폴백)", "\"차가 들어오고 있어요\" + 10m 단위 거리. 도착 전 공조 사전 설정(유아 카시트 좌석 열선 0 고정)", "FusedLocation.registerFusedLocationCallback, TurnSignal.registerTurnSignalState, HVAC.setHvacFanSpeed/Temperature, Seat.setHvacSeatTemperature"],
                 ["3 식별", "—", "차종(VIN) + 번호판 끝 4자리 상시 노출", "CarInfo.getVINInfo (미지원 시 운전자 입력 폴백)"],
                 ["4 정차", "기어 P + 차속 0 + 60m 이내", "\"차가 멈췄어요\" + 간판 기준 앞/뒤 m (FusedLocation 신뢰도 ≥0.70일 때만, 아니면 운전자 원탭 [간판 앞/정면/뒤])", "Gear.registerGearSelection, FusedLocation confidence"],
                 ["5 맞이", "운전자 [주변 확인 완료] (유일한 수동, P단 아니면 거부) → 문 열림 → 착좌", "조수석 잠금 해제·창문 25%(와이퍼 작동 시 0%)·하향등 ON일 때 독서등·TTS \"○○님, 반갑습니다\"", "Door.setDoorLock, Window.setWindowPosition, Light.setReadingLightSwitch, Light.registerLowBeamLightState, Seat.registerSeatOccupancy, TextToSpeech(ON_DEVICE)"],
                 ["6 종료", "승차 좌석 문 닫힘 + 벨트 체결 (센서 없는 트림은 수동 버튼)", "링크 즉시 파기, 20분 TTL 백업, 창문·조명·공조 원복", "Seat.registerSeatBeltBuckledState, Door.registerDoorPosition"]],
                [900, 2600, 3400, 2800])
        + heading("다. 주행 안전·개인정보 설계")
        + bullet("운전 부하 지수(DLI): 조향 각속도·브레이크·와이퍼·차속·다음 회전(NaviHelper TBT)까지 거리로 계산. 0.45 이상이면 일반 안내를 보류했다가 1.5초 안정 후 재생. 차도 쪽 문 경고와 출발 위험 경고만 즉시 발화.")
        + bullet("출발 인터락: 5단계 중 기어 D/R 전환 시 문 열림·벨트 미체결이면 즉시 경고.")
        + bullet("원격 도어 **개방**은 하지 않음. 잠금 해제만, P단 정차 + 운전자 주변 확인 후에만 호출.")
        + bullet("동승자 스마트폰 GPS 미수집(개인정보 보호법 제22조의2 아동 보호 고려). 차량 원시 좌표는 중계 서버가 폐기하고 10m 버킷 거리만 전달. 세션은 탑승 즉시 파기, 미종료 시 20분 자동 만료.")
        + bullet("주행 제한(CarUxRestrictions) 시 입력란 숨김, 터치 타깃 ≥56dp, 상단 카드 높이 고정으로 버튼 위치가 움직이지 않음.")
        + heading("라. 차량 환경만의 우위 (모바일 대비)")
        + table(["", "스마트폰 위치 공유 / 택시 앱", "마중 (Pleos Connect)"],
                [["\"지금 타도 돼요\" 판단", "GPS 속도 추정, 정차 여부 불명", "기어 P + 차속 0 (차량 신호)"],
                 ["잘못된 문 방지", "불가", "차도 쪽 문 열림 즉시 음성 경고"],
                 ["공유 종료", "사용자가 직접 꺼야 함", "벨트 체결 시 자동 파기"],
                 ["운전자 입력", "통화·메신저", "버튼 1개(주변 확인), 나머지 자동"],
                 ["맞이", "없음", "잠금 해제·창문·독서등·공조·환대 음성"]],
                [2300, 3400, 4000])
    )

    c['key_features'] = (
        heading("대표 시나리오 — 맞벌이 학부모의 저녁 학원가 초등 자녀 픽업")
        + bullet("**출발 전:** 차량 화면(또는 Gleo \"마중, 파리바게뜨 대치점으로 지수 픽업\")에서 기준 간판을 고르면 아이 폰으로 일회성 링크가 갑니다. 아이는 설치 없이 웹에서 차종·번호판 끝 4자리(4568)와 \"약 70m 남음\"을 봅니다.")
        + bullet("**접근:** 반경 80m 안에서 감속하며 우측 방향지시등을 켜는 순간 접근 알림이 가고, 차는 조수석 풍량과 온도를 미리 맞춥니다.")
        + bullet("**정차:** 앞차 때문에 간판보다 12m 앞에 P단으로 서는 순간, 아이 화면이 \"차가 멈췄어요 · 간판 앞 12m\"로 바뀝니다. 빌딩숲으로 위치 오차가 크면 거리 문구를 숨기고 부모가 [간판 앞/정면/뒤] 중 하나를 한 번 눌러 전달합니다.")
        + bullet("**맞이:** 부모가 사이드미러로 후방 이륜차를 확인하고 [주변 확인 완료]를 누르면 아이 화면이 \"지금 타도 돼요\"가 되고 조수석이 풀리며 창문이 25% 내려갑니다(우천 시 생략). 아이가 실수로 뒷좌석 차도 쪽 문을 당기면 즉시 \"차도 방향 왼쪽 문이 열렸습니다\"가 울립니다.")
        + bullet("**탑승·종료:** 인도 쪽 문이 열리면 야간 독서등이 켜지고, 착좌하면 \"지수님, 반갑습니다\", 벨트를 매면 링크가 사라지고 창문·조명·공조가 원래대로 돌아갑니다. 문이 열린 채 D단에 넣으면 출발 위험 경고가 납니다.")
        + heading("예외·확장 시나리오")
        + bullet("**우천 역세권 배우자 마중:** 와이퍼 작동(Wiper ON)이 감지되면 창문 개방을 생략하고 잠금만 해제합니다.")
        + bullet("**지하주차장 진입:** FusedLocation 신뢰도가 떨어지면 GPS 거리 안내를 즉시 중단하고 운전자 원탭 지정으로 전환합니다.")
        + bullet("**센서가 없는 트림:** checkSeatOccupancyCapabilities·checkSeatBeltBuckledCapabilities 결과에 따라 도어·벨트 시퀀스 추론 모드 또는 수동 [탑승 확인·종료] 모드로 자동 전환됩니다.")
        + bullet("**어르신 마중:** 무장애 회차 후보 지점 우선, 승차 시 실내 온도 23.5℃로 준비(PassengerProfile.SENIOR).")
    )

    c['pleos_plan'] = (
        heading("가. 현재 구현 상태 (1차 접수 시점)")
        + bullet("Android Kotlin(Compose) 차량 앱 — pleos(실 SDK) / sim(시뮬레이션) 두 플레이버, 동일한 앱 로직. Node.js SSE 중계 서버 + 동승자 무설치 웹. 유닛 테스트 15건, 중계 서버 E2E 통과. sim·pleos 릴리스 APK 빌드 완료.")
        + bullet("Android Automotive OS 에뮬레이터(API 33, 2560×1440 @160dpi — Pleos Connect 에뮬레이터와 같은 화면 스펙)에서 sim APK를 실행해 6단계 전체를 녹화했습니다(시연 영상). AAOS CarPropertyManager 차속 구독이 실제로 동작함을 확인했습니다.")
        + bullet("SDK 버전은 nexus-playground.pleos.ai에 실제 배포된 것만 사용: Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TextToSpeech 3.3.1.")
        + heading("나. SDK / API 사용 계획 (설계 기준 → 구현 파일)")
        + table(["SDK · 도메인", "사용 API", "역할 / 미지원 시 대체", "권한"],
                [["Vehicle · Gear, Brake, SteeringWheel, Wiper, TurnSignal, Light", "register/unregister GearSelection, BrakePedalStatus, SteeringWheelAngle, WiperState(FRONT_WINDSHIELD), TurnSignalState, LowBeamLightState + getCurrent*", "P단 정차 판정, 운전 부하 지수, 접근 복합 조건, 우천 인터락, 야간 독서등 조건. 콜백 실패는 UI 로그로 투명 표시", "CAR_DRIVE, CAR_LIGHTS, READ_CAR_STEERING, READ_WINDSHIELD_WIPERS"],
                 ["Vehicle · Door, Window", "registerDoorPosition×4, setDoorLock(ROW_1_RIGHT), setWindowPosition(ROW_1_RIGHT), checkDoorPosition/DoorLock/WindowPositionCapabilities", "차도 쪽 문 경고, 승차 좌석 문 열림/닫힘, 잠금 해제·창문 25%/0%", "CAR_DOORS, CONTROL_CAR_DOORS, CAR_WINDOWS, CONTROL_CAR_WINDOWS"],
                 ["Vehicle · Seat", "registerSeatOccupancy×3, registerSeatBeltBuckledState×3, get/setHvacSeatTemperature, checkSeatOccupancy/SeatBeltBuckled/SeatTemperatureCapabilities", "착좌 환대, 벨트 체결 자동 종료, 카시트 좌석 열선 0 고정. 미지원 트림은 시퀀스 추론/수동 모드", "CAR_SEATS, CONTROL_CAR_SEATS"],
                 ["Vehicle · HVAC", "get/setHvacFanSpeed, get/setHvacTemperature(CELSIUS), get/setHvacRecirculation, checkHvacFanSpeed/Temperature/RecirculationCapabilities", "도착 전 사전 공조 → 세션 종료 시 스냅샷 원복. AccessType이 WRITE가 아니면 건너뜀", "CAR_CLIMATE, CONTROL_CAR_CLIMATE"],
                 ["Vehicle · CarInfo", "getVINInfo", "차종 표기 (실패 시 운전자 입력 차종)", "CAR_INFO"],
                 ["FusedLocation 1.1.3", "isSupported, initialize, registerFusedLocationCallback, getLastKnownLocation (confidence)", "접근 거리·정차 오프셋. 미지원/저신뢰 → 운전자 원탭 지정", "FUSED_LOCATION"],
                 ["NaviHelper 3.0.5", "requestRoute / requestRouteWithWaypoints / cancelRoute, addListener(onTBTInfo)", "후보 포켓 경로, 다음 회전 거리 → 운전 부하", "NAVI_ROUTE, NAVI_ROUTE_SEARCH"],
                 ["TextToSpeech 3.3.1", "TextToSpeech(ON_DEVICE).speak(text, true)", "환대 인사·안전 경고. 실패 시 Android TTS 폴백", "TTS_SERVICE"],
                 ["AAOS 표준", "CarPropertyManager PERF_VEHICLE_SPEED, CarUxRestrictionsManager", "차속(Pleos SDK 미제공), 주행 중 입력 제한. 권한 미승인 시 FusedLocation 좌표로 차속 추정", "android.car.permission.CAR_SPEED"],
                 ["Gleo", "schema.json: START_SAFE_PICKUP / SHARE_PICKUP_OFFSET / COMPLETE_OR_CANCEL_PICKUP", "음성 호출로 픽업 시작·정차 위치 지정·종료", "—"]],
                [2100, 2900, 3100, 1600])
        + heading("다. 구조")
        + body("Vehicle SDK·AAOS·FusedLocation 콜백 → VehicleSignalHub(단일 StateFlow, 조향 각속도·추정 차속 계산) → SignalDrivenPickupCoordinator(신호 변화로 6단계 자동 전진) → PickupEtaOrchestrator(세션·티켓·VehicleFacts 발행·즉시 파기) → 중계 서버(원시 좌표 폐기) → 동승자 웹. 차량 제어는 BoardingActuator(Door/Window/Light) + PleosCapabilityGateway(공조 스냅샷·원복, 탑승 판정 모드).")
        + heading("라. 2차 PT 심사 전 계획")
        + bullet("Pleos Connect 에뮬레이터 v2.1.0(API 34)에 pleos APK 설치, Virtual Console 신호 주입으로 트림별 check*Capabilities 응답과 폴백(수동 종료 모드, 창문 생략) 실증.")
        + bullet("TTS·FusedLocation 실연동 확인 및 시연 영상 교체, 정차 후 승차 소요 시간 비교 실험(통화 대 마중).")
        + bullet("키즈폰 SMS 폴백 발송 채널 연결(현재 서버 스텁), 다자녀 연속 픽업 큐(경유지 2개 슬라이딩은 구현됨).")
    )

    c['reference_files'] = (
        heading("가. 사용자 가치")
        + bullet("운전자: 정차 구간 통화·메신저 확인 0회가 목표. 위치 안내·접근 알림·종료가 모두 자동이라 운전자는 주변 확인에만 집중합니다.")
        + bullet("동승자: 번호판 대조 + 차량이 확인한 \"멈춤\" 신호 + 잘못된 문 경고로 오승차와 차도 진입 위험을 줄입니다.")
        + bullet("현대차그룹: 운전자 1명당 비운전 가족 1.5~2명이 설치 없이 웹으로 SDV 경험을 접하는, 차량 밖으로 확장되는 접점입니다.")
        + heading("나. 타깃 고객과 규모 추정 (가정은 [가정]으로 표기)")
        + table(["단계", "근거 / 계산", "값"],
                [["초등학생 수", "2024 사교육비 조사(교육부·통계청)", "약 250만 명"],
                 ["사교육 참여율(초등)", "같은 조사", "87.7%"],
                 ["18세 미만 자녀 가구 맞벌이 비율", "통계청 2024 하반기 맞벌이 가구 고용현황", "58.5%"],
                 ["반복 픽업 수요 가구", "250만 × 87.7% × 58.5% ÷ 가구당 자녀 1.5명 [가정]", "약 85만 가구"],
                 ["전국 가구 대비 비율", "85만 ÷ 약 2,200만 가구", "약 3.9%"],
                 ["Pleos Connect 국내 보급 [가정]", "2026 그랜저부터 순차 적용, 2030년 글로벌 2,000만 대(그룹 발표)", "2027년 50만 · 2028년 150만 · 2030년 500만 대"]],
                [2600, 5100, 2000])
        + table(["연도", "잠재 차량 (보급 × 3.9%)", "설치율 [가정]", "운전자 MAU [가정]", "동승자 웹 접점 (×1.7)"],
                [["2027", "약 2.0만 대", "50%", "약 6,000", "약 1.0만"],
                 ["2028", "약 5.9만 대", "50%", "약 1.8만", "약 3.0만"],
                 ["2030", "약 19.5만 대", "55%", "약 6.4만", "약 11만"]],
                [1200, 2500, 1600, 2200, 2200])
        + bullet("**Retention Rate 목표:** 픽업은 주 3~5회 반복되는 루틴이라 학기 중 4주 리텐션 55%, 12주 45%를 목표로 합니다[가정]. 지표: 세션 완료율(벨트 체결로 자동 종료된 비율), 운전자 수동 지정 비율, 접근 알림 → 승차까지 소요 시간. 방학에는 역·학원 외 '퇴근 마중' 시나리오로 이탈을 줄입니다.")
        + heading("다. 수익 모델과 확장 로드맵")
        + bullet("1단계(출시): 기본 픽업·안심 확인 무상 — 재사용 습관 검증.")
        + bullet("2단계: 다자녀 학원 일정 연동, 키즈폰 SMS 알림 등 통신 비용 발생 기능을 선택형 구독(월 2,900원 [가정])으로.")
        + bullet("3단계: 현대 카페이 제휴 주차장의 단기 무료 회차 구역 안내 연계로 학원가 불법 주정차 부담 완화.")
        + bullet("4단계(B2B): 학원 셔틀·어린이집 승하차 확인 — 벨트 체결·문 닫힘 로그로 '안전 승하차 확인서' 자동 생성.")
        + bullet("글로벌: 미국 초등학교 'car line' 픽업(2027년 Tucson부터 Pleos Connect 적용) — 번호판·정차 신호 기반 흐름은 언어와 무관하게 동일하게 동작합니다.")
    )

    ref = (
        bullet(f"**GitHub 저장소(소스·APK):** {GITHUB} — 2026.11.16~11.23 Public 유지. 최종 커밋 해시는 제출 이메일 본문에 기재.")
        + bullet("**릴리스 APK:** apk/ 폴더 — majung-v2-pleos-release.apk (Pleos Connect 에뮬레이터 v2.1.0 / API 34 대상, Vehicle 2.3.2), majung-v2-sim-release.apk (일반 AVD·AAOS 에뮬레이터 시연용). 두 APK 모두 debug 키 서명으로 바로 설치됩니다.")
        + bullet("**시연 영상:** docs/demo/majung_demo.mp4 (이메일 첨부 동일 파일) — Android Automotive 에뮬레이터(2560×1440) 실제 앱 화면 녹화 + 동승자 웹 실시간 상태 재생, 약 1분 20초.")
        + bullet("**검증 방법(저장소에서 재현):** node relay-server/verify_e2e_sse.mjs · ./gradlew :app:testSimDebugUnitTest (15건) · ./scripts/verify_and_build_apk.sh")
    )
    if ivi_png and ivi_png.exists():
        ref += images.add(ivi_png, 16.0, "그림 1. 차량 화면(17\" 가로, Pleos ccNC 다크 IVI 언어) — 5단계 승차 맞이 중. 우측 패널이 차량 신호(기어·차속·문·착좌·벨트·하향등)를 실시간으로 보여 준다.")
    if web_png and web_png.exists():
        ref += images.add(web_png, 7.0, "그림 2. 동승자 무설치 웹(현대 브랜드 라이트 톤) — 상태 카드는 차량 신호(P단 정차·문·벨트)로만 바뀐다.")
    c['reference_link'] = ref

    c['shortform_link'] = body("미참여 (공란 제출)")
    return c


# ------------------------------------------------------------------ 적용
def fill(official: Path, out: Path, ivi_png: Path | None, web_png: Path | None):
    tmp = Path(tempfile.mkdtemp(prefix='majung-form-'))
    with zipfile.ZipFile(official) as z:
        z.extractall(tmp)
    doc_path = tmp / 'word' / 'document.xml'
    xml = doc_path.read_text(encoding='utf-8')
    images = Images(tmp)
    blocks = content(images, ivi_png, web_png)

    # 1) 각 입력란(sdt)이 들어 있는 문단 전체를 생성 블록으로 교체
    for tag, block in blocks.items():
        m = re.search(r'<w:p\b(?:(?!<w:p\b).)*?<w:tag w:val="' + re.escape(tag) + r'"/>.*?</w:sdt></w:p>', xml, flags=re.S)
        if not m:
            raise SystemExit(f'input box not found: {tag}')
        xml = xml[:m.start()] + block + xml[m.end():]

    # 2) 앱명 / 프로젝트명 공란 채우기 (01·02·04): 해당 문단 안의 첫 밑줄 묶음만 앱명으로 교체
    filled = 0
    def fill_app_name(m):
        nonlocal filled
        p = m.group(0)
        text = ''.join(re.findall(r'<w:t[^>]*>(.*?)</w:t>', p, flags=re.S))  # run이 쪼개져 있어도 문단 텍스트로 판정
        if '앱명 / 프로젝트명' not in text or '_____' not in text:
            return p
        filled += 1
        p = re.sub(r'_{5,}', '마중 (MAJUNG)', p, count=1)
        return re.sub(r'_{2,}', '', p)  # run이 쪼개져 남은 밑줄 제거
    xml = re.sub(r'<w:p\b(?:(?!<w:p\b).)*?</w:p>', fill_app_name, xml, flags=re.S)
    print(f'app-name fields filled={filled}')

    doc_path.write_text(xml, encoding='utf-8')
    images.save()
    if out.exists():
        out.unlink()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(tmp.rglob('*')):
            if p.is_file():
                z.write(p, p.relative_to(tmp).as_posix())
    shutil.rmtree(tmp)
    print('written', out)


if __name__ == '__main__':
    a = sys.argv[1:]
    if len(a) < 2:
        raise SystemExit(__doc__)
    ivi = Path(a[a.index('--ivi') + 1]) if '--ivi' in a else None
    web = Path(a[a.index('--web') + 1]) if '--web' in a else None
    fill(Path(a[0]), Path(a[1]), ivi, web)
