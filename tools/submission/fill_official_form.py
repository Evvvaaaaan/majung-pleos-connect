#!/usr/bin/env python3
"""
공식 접수양식(contest-application-consent.docx)의 02 '앱 프로젝트 소개 / 심사 자료' 입력란을 마중(MAJUNG) 내용으로 채운다.

- 01 참가신청서 · 03/04 동의·서명은 개인정보라 비워 둔다(참가자가 직접 기입·서명).
- 앱명 / 프로젝트명 공란만 "마중 (MAJUNG)"으로 채운다.
- 사용: python3 tools/submission/fill_official_form.py <official.docx> <out.docx> [--ivi img.png] [--web img.png] [--icon img.png] [--suggestion img.png]
        [--personal 개인정보.json]  ← 01 참가신청서·04 서명표까지 채운 제출용 사본. JSON 은 저장소 밖에 두고 결과물도 저장소에 넣지 않는다.
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
TEAM_NAME = "마중"   # 팀명 = 서비스명

def content(images: Images, ivi_png: Path | None, web_png: Path | None, icon_png: Path | None = None, suggestion_png: Path | None = None):
    c = {}

    # 1. 앱 한 줄 소개 — 양식이 한 문장을 요구하므로 한 문장으로 끝낸다.
    c['app_summary'] = (
        body("마중(MAJUNG)은 픽업의 마지막 100m에서 차와 동승자가 **한 지도에서 서로를 보고**, 스마트폰만으로는 확인할 수 없는 세 가지"
             "(**차가 정말 P단에 멈췄는지, 어느 문이 열렸는지, 안전벨트를 맸는지**)를 Pleos Vehicle SDK 신호로 확인해 "
             "\"지금 타도 돼요\"를 알린 뒤, 벨트를 매는 순간 위치 공유를 스스로 끝내는 설치 없는 안심 픽업 앱입니다.")
    )

    # 2. 기획 배경 — 심사 질문 "운전자 및 동승자의 Pain Points & Needs 가 명확히 정의되고 해결 방식이 명확한가" 에 맞춘다.
    c['planning_background'] = (
        body("내비게이션이 \"목적지에 도착했습니다\"라고 말한 뒤부터 아이가 차 문을 열기까지, 이 **마지막 100m**가 학부모와 아이에게 가장 혼란스러운 구간입니다. "
             "위치 공유·도착 예정 시간·번호판 표시는 택시 호출 앱이 이미 합니다. 아직 풀리지 않은 불편은 스마트폰만으로는 확인할 수 없는 것들입니다.")
        + heading("운전자·동승자의 Pain Points & Needs")
        + table(["누가", "Pain Point (불편한 순간)", "Need", "지금 쓰는 대안의 한계"],
                [["운전자(부모)", "야간·우천의 학원가 연석에 비슷한 SUV가 줄지어 서고, 앞차 때문에 약속한 간판보다 두세 대 앞에 선다. 아이는 차도 쪽으로 내려오거나 반대로 걷는다.", "통화 없이 \"여기 섰다\"와 \"지금 타라\"를 알리고, 주변 확인에만 집중하고 싶다.", "전화·메신저: 정차 자리를 찾으며 \"어디야?\"를 반복하는 시각·인지 분산. 어린이보호구역은 정차가 제한되어(도로교통법 제32조 제8호) 통화가 길어진다."],
                 ["동승자(아이)", "어느 차인지, 지금 타도 되는지, 어느 문을 열어야 하는지 모른다. 달리는 차도 쪽 문을 당기기도 한다.", "차가 **멈춘 것이 확인된 뒤** 인도 쪽 문으로 타고 싶다. 앱 설치나 로그인은 없어야 한다.", "폰 위치 공유·택시 앱: 위치와 번호판은 보여 주지만 **멈췄는지(기어 P), 어느 문인지, 벨트를 맸는지**는 모른다. 이 세 가지는 차량 신호에서만 나온다."],
                 ["보호자(집)", "탔는지, 끝났는지 알 수 없다. 공유를 끄는 것을 잊어 위치가 계속 노출된다.", "탑승 완료를 자동으로 알고, 공유는 저절로 끝나야 한다.", "위치 공유 앱: 사용자가 직접 꺼야 하고, 아이 위치가 서버와 가족 폰에 남는다."]],
                [1200, 3100, 2500, 2900])
        + heading("왜 지금, 왜 차에서 풀어야 하는가")
        + bullet("**사고는 픽업 시간대에 몰린다.** 12세 이하 어린이 교통사고의 63.1%가 등하교 시간대(08~10시, 12~18시)에 일어납니다(도로교통공단 경기지역본부 최근 5년 집계, 2024.6 보도). 마중의 대표 무대인 대치동 학원가의 보행자 교통사고는 2021년 28건에서 2023년 60건, 2024년 56건으로 늘었습니다(서울시의회 분석, 2026.1 보도).")
        + bullet("**오래 서 있을 수 없다.** 학원가·역 앞은 주정차 단속 구간이라(서울시 2024년 불법 주·정차 단속 221만 건) 차는 '멈춘 그 순간'에 바로 태우고 떠나야 합니다. 그래서 \"지금 타도 돼요\"는 GPS 추정이 아니라 **기어 P + 차속 0** 같은 차량 신호로 판단해야 합니다.")
        + bullet("**매일 반복되는 가장 흔한 가족 주행이다.** 초등학생 약 250만 명 중 87.7%가 사교육에 참여하고(교육부·통계청 2024년 사교육비 조사), 18세 미만 자녀 가구의 58.5%가 맞벌이입니다(통계청 2024년 하반기 맞벌이 가구 고용현황). 저녁 학원 픽업은 주 3~5회 반복되는 루틴이라, 한 번 익힌 습관이 그대로 리텐션이 됩니다.")
        + bullet("**해결 방식:** 차량이 감지하는 세 가지 신호(P단 정차, 문 위치, 벨트)를 동승자 웹에 그대로 보여 주고, 운전자가 누르는 버튼은 [여기 못 서요]와 [주변 확인 끝, 문 열기] 둘뿐입니다. 접근 알림·정차 확인·맞이·종료는 모두 차량 신호에 따라 자동으로 진행됩니다.")
        + note("※ 출처: 교육부·통계청 「2024년 초중고 사교육비 조사」(2025.3), 통계청 「2024년 하반기 지역별고용조사 맞벌이 가구 및 1인 가구 취업 현황」(2025.6), 도로교통공단 경기지역본부 어린이 교통사고 분석(경기일보 2024.6.6 보도), 서울시의회 대치동 학원가 보행자 사고 분석(한국경제 2026.1.4 보도), 서울특별시 교통분야 누리집 「불법 주·정차 단속 실적」(2024년 합계, 2026.6.30 기준).")
    )

    # 3. 앱 주요 기능 및 차량 특화 요소 — 소제목은 심사 항목 '차량특화성'의 세부 질문 문구를 그대로 따른다.
    c['vehicle_scenario'] = (
        heading("한눈에")
        + table(["", "마중 (MAJUNG)"],
                [["문제", "픽업의 마지막 100m. 차와 아이가 서로를 못 찾고, 폰은 '멈췄는지·어느 문인지·벨트를 맸는지'를 모른다."],
                 ["해결", "차량 화면과 동승자 웹이 **같은 지도**에서 서로를 보고, 세 가지는 **차량 신호**로 확인해 \"지금 타도 돼요\"를 알린다. 벨트를 매면 공유가 저절로 끝난다."],
                 ["차량 신호로 확인하는 3가지", "기어 P + 차속 0이면 멈춤 확정 / 열린 문이 차도 쪽이면 즉시 음성 경고 / 벨트를 매면 링크 파기·실내 원복"],
                 ["운전자가 하는 일", "버튼 둘: [여기 못 서요](건너편 U턴 · 앞으로 30m · 한 바퀴) 와 [주변 확인 끝, 문 열기]. 나머지는 자동."],
                 ["동승자가 하는 일", "설치 없이 링크를 열고, 필요하면 지도에서 탈 자리를 골라 \"여기서 탈게요\"를 보낸다."],
                 ["검증", "Pleos Connect Emulator v2.1.0에서 pleos 빌드 실행: Vehicle SDK 등록 16건·콜백 수신·제어 onComplete, NaviHelper 재경로, 동승자 위치·제안 수락. 유닛 테스트 56건, 중계 서버 E2E."],
                 ["산출물", "GitHub(소스·release APK 2종), 시연 영상, 에뮬레이터 검증 로그·스크린샷, 심볼·디자인 시안."]],
                [1700, 8000])
        + heading("가. 모바일 대비 차량 환경만의 독자적인 가치와 우위 — 차량 신호로만 확인할 수 있는 3가지 (구현·검증 완료)")
        + table(["스마트폰만으로는 알 수 없는 것", "차량이 확인하는 방법 (Vehicle SDK 신호)", "마중이 하는 일"],
                [["차가 정말 멈췄는지", "Gear.registerGearSelection = PARK\n+ 차속 0 (AAOS PERF_VEHICLE_SPEED)", "그때에만 동승자 웹의 상태 카드가 \"지금 타도 돼요\"로 바뀜. 차속 0이어도 D단이면 바뀌지 않음(테스트로 보장)"],
                 ["동승자가 어느 문을 열었는지", "Door.registerDoorPosition(ROW_2_LEFT) 열림", "운전 부하 게이트를 우회하는 CRITICAL 실내 음성 \"차도 방향 왼쪽 문이 열렸습니다. 인도 쪽 오른쪽 문을 이용해 주세요.\""],
                 ["안전벨트를 맸는지", "Seat.registerSeatBeltBuckledState + 도어 닫힘", "일회성 공유 링크를 서버에서 즉시 삭제(DELETE), 창문·독서등·공조를 세션 전 상태로 원복"]],
                [2300, 3400, 4000])
        + heading("나. 주행·정차 맥락 및 차량 기능과의 유기적 결합 — 6단계, 트리거는 모두 차량 신호")
        + table(["단계", "트리거 (차량 신호)", "차량 → 동승자 웹 / 차량 제어", "SDK · API"],
                [["1 합의", "운전자 \"픽업 시작\" 또는 Gleo 음성 START_SAFE_PICKUP", "기준 간판·차종·번호판 끝 4자리·일회성 웹 링크(최대 20분)", "NaviHelper.requestRoute(WithWaypoints), CarInfo.getVINInfo"],
                 ["2 접근", "반경 80m + ≤25km/h + 우측 방향지시등 (또는 50m 저속 폴백)", "지도에 내 차·동승자·승차 지점, \"차가 오고 있어요\" + 거리. 운전자가 [여기 못 서요]를 누르면 건너편(U턴)·앞으로 30m·한 바퀴 중 하나로 승차 지점이 옮겨지고, 재경로와 동승자 웹 반영이 즉시 따라옵니다. 동승자는 지도에서 탈 자리를 골라 \"여기서 탈게요\"(또는 건너편으로 갈게요 / 2분만 기다려 주세요)를 보내고, 차량 화면에는 카드로 도착해 [수락]/[나중에]로 답합니다. 수락하면 그 좌표로 승차 지점 이동·재경로. 도착 전 공조 사전 설정(유아 카시트 좌석 열선 0 고정)", "FusedLocation.registerFusedLocationCallback, TurnSignal.registerTurnSignalState, NaviHelper.requestRoute(재경로), HVAC.setHvacFanSpeed/Temperature, Seat.setHvacSeatTemperature"],
                 ["3 식별", "—", "차종(VIN) + 번호판 끝 4자리 상시 노출", "CarInfo.getVINInfo (미지원 시 운전자 입력 폴백)"],
                 ["4 정차", "기어 P + 차속 0 + 60m 이내", "\"차가 멈췄어요\" + 지도상 정차 위치 (Gleo 음성 SHARE_PICKUP_OFFSET으로 간판 앞/정면/뒤 지정도 가능)", "Gear.registerGearSelection, FusedLocation confidence"],
                 ["5 맞이", "운전자 [주변 확인 끝, 문 열기](유일한 수동 입력, P단이 아니면 거부), 이어서 문 열림·착좌", "조수석 잠금 해제·창문 25%(와이퍼 작동 시 0%)·하향등 ON일 때 독서등·TTS \"○○님, 반갑습니다\"", "Door.setDoorLock, Window.setWindowPosition, Light.setReadingLightSwitch, Light.registerLowBeamLightState, Seat.registerSeatOccupancy, TextToSpeech(ON_DEVICE)"],
                 ["6 종료", "승차 좌석 문 닫힘 + 벨트 체결 (센서 없는 트림은 수동 버튼)", "링크 즉시 파기, 20분 TTL 백업, 창문·조명·공조 원복", "Seat.registerSeatBeltBuckledState, Door.registerDoorPosition"]],
                [900, 2600, 3400, 2800])
        + heading("다. 차량에서 새로 생기는 경험")
        + bullet("**화면에는 지도와 지금 누를 버튼만 둡니다.** 차량 화면은 지도 위 동승자·내 차·승차 지점(현대 엠블럼)과 상태 판 하나, 아래 버튼 한 줄뿐입니다. 주행 중에는 입력란을 숨기고, 동승자 제안은 카드로 도착해 [수락]/[나중에]로 끝납니다.")
        + bullet("**동승자는 설치 없이 차와 같은 지도를 봅니다.** 일회성 링크 하나로 차의 위치·승차 지점·번호판 끝 4자리를 보고, 지도를 움직여 탈 자리를 골라 보내면 그 좌표가 차량에 전달되고 수락 즉시 내비가 재경로합니다.")
        + bullet("**차가 동승자를 맞이합니다.** P단 정차 후 운전자가 주변을 확인하면 조수석 잠금 해제·창문 25%(우천 시 생략)·야간 독서등이 차례로 켜지고, 착좌하면 \"○○님, 반갑습니다\"가 나옵니다.")
        + bullet("**공유는 자동으로 끝납니다.** 벨트를 매는 순간 링크가 서버에서 삭제되고 창문·조명·공조가 세션 전 상태로 돌아갑니다. 운전자도 동승자도 끌 필요가 없습니다.")
        + heading("라. 주행 안전·개인정보 설계")
        + bullet("운전 부하 지수(DLI): 조향 각속도·브레이크·와이퍼·차속·다음 회전(NaviHelper TBT)까지 거리로 계산. 0.45 이상이면 일반 안내를 보류했다가 1.5초 안정 후 재생. 차도 쪽 문 경고와 출발 위험 경고만 즉시 발화.")
        + bullet("출발 인터락: 맞이 단계(5단계)에서 기어를 D/R로 바꿀 때 문이 열려 있거나 벨트가 풀려 있으면 즉시 경고.")
        + bullet("원격 도어 **개방**은 하지 않음. 잠금 해제만, P단 정차 + 운전자 주변 확인 후에만 호출.")
        + bullet("위치는 서로 보기 위해서만 씁니다. 동승자 위치는 동승자 웹에서 브라우저 위치 권한을 허용한 경우에만(opt-in) 세션 동안 중계 서버 메모리에 머물고, 차량 위치·승차 지점과 함께 탑승 확인 즉시 삭제(DELETE), 미종료 시 20분 자동 만료. 디스크·로그에 남기지 않음(개인정보 보호법 제22조의2 아동 보호 고려).")
        + bullet("주행 제한(CarUxRestrictions) 시 입력란 숨김, 터치 타깃 ≥56dp, 버튼은 화면 아래 한 줄에 고정되어 주행 중 위치가 움직이지 않음. 차량 신호·로그는 메인 화면에서 빼고 진단 창으로 이동.")
        + bullet("중계 서버 접근 제어: 세션 id만으로는 읽기·쓰기 불가. 차량은 세션 생성 시 등록한 키(헤더)로만 상태 갱신·제안 응답·파기를 할 수 있고, 동승자는 공유 링크의 일회성 토큰 없이는 상태 조회·스트림·위치 전송·제안을 할 수 없음. 키·토큰은 스트림에 실리지 않으며 불일치는 403 (E2E 검증 포함).")
        + heading("마. 기존 대안과의 차이 — 기존에 없던 것")
        + body("픽업 장소와 위치·도착 예정 시간을 공유하고 그 변경을 내비에 반영하는 일은 폰과 내비 연동만으로도 됩니다. 마중은 그 위에, 차량 신호가 없으면 만들 수 없는 세 가지를 더했습니다. 첫째, 기어 P와 차속 0이 확인된 뒤에만 \"지금 타도 돼요\"를 알립니다. 둘째, 차도 쪽 문이 열리면 그 순간 차가 경고합니다. 셋째, 벨트를 매면 공유가 저절로 지워집니다. 동승자는 계정도 설치도 없이 링크 하나로 참여합니다.")
        + table(["", "전화·메신저", "위치·도착 시간 공유형 (지도 앱·택시 앱·픽업 공유 앱)", "키즈폰·가족 위치 앱", "마중 (Pleos Connect)"],
                [["\"지금 타도 돼요\" 판단", "말로 확인", "GPS 속도 추정, 정차 여부 불명", "불명", "**기어 P + 차속 0** (차량 신호)"],
                 ["잘못된 문 방지", "불가", "불가", "불가", "차도 쪽 문 열림 **즉시 음성 경고**"],
                 ["공유 종료", "—", "사용자가 직접 꺼야 함", "상시 공유", "**벨트 체결 시 자동 파기**, 20분 만료"],
                 ["운전자 입력", "통화", "앱 조작", "앱 조작", "버튼 둘, 나머지 자동. 주행 중 입력 숨김"],
                 ["동승자 준비물", "전화", "앱 설치 또는 계정 로그인", "전용 기기", "**링크 하나, 설치 없음**"],
                 ["맞이", "없음", "없음", "없음", "잠금 해제·창문·독서등·공조·환대 음성"],
                 ["서로의 위치", "말로 설명", "폰끼리 공유", "폰끼리 공유", "차량 화면과 동승자 웹이 **같은 지도**, 자리 변경이 내비 재경로로 이어짐"]],
                [1700, 1500, 2100, 1700, 2700])
    )

    c['key_features'] = (
        heading("대표 시나리오 — 맞벌이 학부모의 저녁 학원가 초등 자녀 픽업")
        + bullet("**출발 전:** 차량 화면(또는 Gleo \"마중, 파리바게뜨 대치점으로 지수 픽업\")에서 기준 간판과 동승자를 고르면 아이 휴대폰 번호로 일회성 링크가 문자로 갑니다(현재 구현은 차량 화면의 QR·링크 공유까지, 번호 문자 발송은 2차 전 연결). 아이는 설치 없이 웹에서 차종·번호판 끝 4자리(4568)와 \"약 70m 남음\"을 봅니다.")
        + bullet("**접근:** 반경 80m 안에서 감속하며 우측 방향지시등을 켜는 순간 접근 알림이 가고, 차는 조수석 풍량과 온도를 미리 맞춥니다.")
        + bullet("**정차 자리 조정:** 간판 앞에 앞차가 서 있으면 부모는 [여기 못 서요] 한 번으로 \"앞으로 30m\"를 고릅니다. 승차 지점이 지도에서 옮겨지고 내비가 재경로하며 아이 화면에는 \"운전자: 앞으로 30m\"와 새 승차 지점이 뜹니다. 아이가 지도를 움직여 탈 자리를 고르고 \"여기서 탈게요\"를 보내면 차량 화면에 \"승차 지점에서 92m\" 카드로 도착하고, 부모가 [수락]하면 승차 지점이 그 자리로 옮겨집니다. P단으로 서는 순간 아이 화면이 \"차가 멈췄어요\"로 바뀝니다.")
        + bullet("**맞이:** 부모가 사이드미러로 후방 이륜차를 확인하고 [주변 확인 끝, 문 열기]를 누르면 아이 화면이 \"지금 타도 돼요\"가 되고 조수석이 풀리며 창문이 25% 내려갑니다(우천 시 생략). 아이가 실수로 뒷좌석 차도 쪽 문을 당기면 즉시 \"차도 방향 왼쪽 문이 열렸습니다\"가 울립니다.")
        + bullet("**탑승·종료:** 인도 쪽 문이 열리면 야간 독서등이 켜지고, 착좌하면 \"지수님, 반갑습니다\", 벨트를 매면 링크가 사라지고 창문·조명·공조가 원래대로 돌아갑니다. 문이 열린 채 D단에 넣으면 출발 위험 경고가 납니다.")
        + heading("예외·확장 시나리오")
        + bullet("**우천 역세권 배우자 마중:** 와이퍼 작동(Wiper ON)이 감지되면 창문 개방을 생략하고 잠금만 해제합니다.")
        + bullet("**지하주차장 진입:** FusedLocation 신뢰도가 떨어지면 거리 숫자를 숨기고 지도와 동승자 제안으로만 안내합니다.")
        + bullet("**센서가 없는 트림:** checkSeatOccupancyCapabilities·checkSeatBeltBuckledCapabilities 결과에 따라 도어·벨트 시퀀스 추론 모드 또는 수동 [탑승 확인·종료] 모드로 자동 전환됩니다.")
        + bullet("**어르신 마중:** 무장애 회차 후보 지점 우선, 승차 시 실내 온도 23.5℃로 준비(PassengerProfile.SENIOR).")
    )

    c['pleos_plan'] = (
        heading("가. 현재 구현 상태 (1차 접수 시점)")
        + bullet("Android Kotlin(Compose) 차량 앱 — pleos(실 SDK) / sim(시뮬레이션) 두 플레이버, 동일한 앱 로직. Node.js SSE 중계 서버 + 동승자 무설치 웹. 유닛 테스트 56건, 중계 서버 E2E 통과. sim·pleos 릴리스 APK 빌드 완료. 지도: 차량은 osmdroid(OSM 타일, 밤에는 색 행렬 필터), 동승자 웹은 Leaflet.")
        + bullet("**Pleos Connect Emulator v2.1.0(API 34, 2560×1440)에서 pleos 빌드를 실행·검증했습니다.** Vehicle SDK 신호 등록(register) 16건, Virtual Console로 주입한 기어·차속·브레이크·조향·방향지시등·하향등·와이퍼·도어·착좌 콜백 수신, 제어 호출 완료(onComplete, 도어 잠금 해제·조수석 창문·독서등), NaviHelper 경로 요청·재경로·TBT 수신, 능력 조회(checkXXXCapabilities), OSM 지도 타일 로드, 동승자 위치·제안 SSE 수신, 동승자가 지도에서 고른 자리의 차량 지도 표시(마중 심볼)와 수락 시 승차 지점 이동·재경로, [여기 못 서요] 3안, 하향등 연동 낮/밤 전환, 진단 창의 동승자 링크 QR까지 확인했습니다. 로그·화면은 저장소 docs/verification, docs/screenshots에 있습니다.")
        + bullet("에뮬레이터에서 확인한 차종별 차이는 코드에서 처리했습니다: DOOR_LOCK이 좌석별 영역을 거부해 능력 조회가 알려 준 지원 영역(4도어 묶음)을 쓰고, WINDOW_POS 범위가 0~10이라 AreaBounds로 %를 환산하며, Pleos TTS ON_DEVICE 엔진 오류는 EventListener.onError로 받아 Android TTS로 폴백합니다.")
        + bullet("차량 위치만은 Pleos 에뮬레이터에 주입 수단이 없어 디버그 빌드의 adb 훅(io.majung.pleos.DEBUG_LOCATION)으로 넣어 접근·정차 판정과 지도를 검증·녹화했습니다(release APK에는 없음, 실차 위치가 들어오면 그대로 우선). AAOS CarPropertyManager 차속 구독은 Pleos·AAOS 에뮬레이터 모두에서 동작했습니다.")
        + bullet("SDK 버전은 nexus-playground.pleos.ai에 실제 배포된 것만 사용: Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TextToSpeech 3.3.1.")
        + heading("나. SDK / API 사용 계획 (설계 기준 → 구현 파일)")
        + table(["SDK · 도메인", "사용 API", "역할 / 미지원 시 대체", "권한"],
                [["Vehicle · Gear, Brake, SteeringWheel, Wiper, TurnSignal, Light", "register/unregister GearSelection, BrakePedalStatus, SteeringWheelAngle, WiperState(FRONT_WINDSHIELD), TurnSignalState, LowBeamLightState + getCurrent*", "P단 정차 판정, 운전 부하 지수, 접근 복합 조건, 우천 인터락, 야간 독서등 조건. 콜백 실패는 UI 로그로 투명 표시", "CAR_DRIVE, CAR_LIGHTS, READ_CAR_STEERING, READ_WINDSHIELD_WIPERS"],
                 ["Vehicle · Door, Window", "registerDoorPosition×4, setDoorLock(ROW_1_RIGHT), setWindowPosition(ROW_1_RIGHT), checkDoorPosition/DoorLock/WindowPositionCapabilities", "차도 쪽 문 경고, 승차 좌석 문 열림/닫힘, 잠금 해제·창문 25%/0%", "CAR_DOORS, CONTROL_CAR_DOORS, CAR_WINDOWS, CONTROL_CAR_WINDOWS"],
                 ["Vehicle · Seat", "registerSeatOccupancy×3, registerSeatBeltBuckledState×3, get/setHvacSeatTemperature, checkSeatOccupancy/SeatBeltBuckled/SeatTemperatureCapabilities", "착좌 환대, 벨트 체결 자동 종료, 카시트 좌석 열선 0 고정. 미지원 트림은 시퀀스 추론/수동 모드", "CAR_SEATS, CONTROL_CAR_SEATS"],
                 ["Vehicle · HVAC", "get/setHvacFanSpeed, get/setHvacTemperature(CELSIUS), get/setHvacRecirculation, checkHvacFanSpeed/Temperature/RecirculationCapabilities", "도착 전 사전 공조, 세션 종료 시 스냅샷으로 원복. AccessType이 WRITE가 아니면 건너뜀", "CAR_CLIMATE, CONTROL_CAR_CLIMATE"],
                 ["Vehicle · CarInfo", "getVINInfo", "차종 표기 (실패 시 운전자 입력 차종)", "CAR_INFO"],
                 ["FusedLocation 1.1.3", "isSupported, initialize, registerFusedLocationCallback, getLastKnownLocation (confidence)", "지도의 내 차 위치, 접근 거리·정차 판정. 미지원·저신뢰 시 거리 숫자 숨김", "FUSED_LOCATION"],
                 ["NaviHelper 3.0.5", "requestRoute / requestRouteWithWaypoints / cancelRoute, addListener(onTBTInfo)", "후보 포켓 경로, [여기 못 서요]·제안 수락 시 승차 지점 재경로, 다음 회전까지의 거리로 운전 부하 계산", "NAVI_ROUTE, NAVI_ROUTE_SEARCH"],
                 ["TextToSpeech 3.3.1", "TextToSpeech(ON_DEVICE).speak(text, true)", "환대 인사·안전 경고. 실패 시 Android TTS 폴백", "TTS_SERVICE"],
                 ["AAOS 표준", "CarPropertyManager PERF_VEHICLE_SPEED, CarUxRestrictionsManager", "차속(Pleos SDK 미제공), 주행 중 입력 제한. 권한 미승인 시 FusedLocation 좌표로 차속 추정", "android.car.permission.CAR_SPEED"],
                 ["Gleo", "schema.json: START_SAFE_PICKUP / SHARE_PICKUP_OFFSET / COMPLETE_OR_CANCEL_PICKUP", "음성 호출로 픽업 시작·정차 위치 지정·종료", "—"]],
                [2100, 2900, 3100, 1600])
        + heading("다. 구조")
        + body("차량 신호는 한 방향으로만 흐릅니다. ① Vehicle SDK·AAOS·FusedLocation 콜백을 VehicleSignalHub가 하나의 StateFlow로 모읍니다(조향 각속도·추정 차속 계산). ② SignalDrivenPickupCoordinator가 신호 변화만으로 6단계를 자동 전진시킵니다. ③ PickupEtaOrchestrator가 세션·티켓·VehicleFacts·지도용 LiveMapState를 발행하고, [여기 못 서요] 3안·제안 수락·즉시 파기를 처리합니다. ④ 중계 서버(세션 메모리, SSE)를 거쳐 동승자 웹(Leaflet 지도)에 같은 상태가 도착합니다. 차량 제어는 BoardingActuator(Door/Window/Light)와 PleosCapabilityGateway(공조 스냅샷·원복, 탑승 판정 모드)가 맡습니다.")
        + heading("라. 2차 PT 심사 전 계획")
        + bullet("**심사위원이 자기 폰으로 타 보는 시연:** 중계 서버를 공개 HTTPS 주소로 상시 운영하고 APK 기본 주소를 그쪽으로 바꿔, 심사위원이 서버 준비 없이 APK만 설치하면 되게 합니다(지금은 저장소의 서버를 로컬에서 실행). 차량 화면(진단 창)에 이미 띄우는 동승자 링크 QR을 이 서버로 연결해, 심사위원 폰의 브라우저 위치 공유와 \"여기서 탈게요\"까지 실제로 동작하게 합니다(폰 브라우저의 위치 공유는 HTTPS에서만 허용).")
        + bullet("**운전 중 손대지 않는 수락:** 지금은 카드의 [수락]/[나중에]를 터치로 답합니다. 이를 SpeechToText SDK로 \"수락\"/\"나중에\" 음성 응답까지 받도록 넓힙니다.")
        + bullet("**안정성:** 중계 끊김·앱 재시작 시 세션 복구, 20분 만료 처리, 장시간 구동 테스트(전시용 APK 기준). 정차 후 승차 소요 시간 비교 실험(통화 대 마중).")
        + bullet("실차 또는 TTS 엔진·위치가 살아 있는 환경에서 Pleos TTS 환대 음성, FusedLocation 기반 자동 접근·정차, 벨트 체결 자동 종료를 실증하고 시연 영상 교체. 휴대폰 번호로 링크 전달: 정차 중 차량 연락처에서 동승자를 고르거나 번호를 입력하면 중계 서버가 문자로 일회성 링크를 보냅니다. 번호는 발송에만 쓰고 저장하지 않습니다(현재는 발송 스텁과 QR·링크 공유까지 구현). 다자녀 연속 픽업 큐(경유지 2개 슬라이딩은 구현됨).")
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
        + bullet("**Retention Rate 목표:** 픽업은 주 3~5회 반복되는 루틴이라 학기 중 4주 리텐션 55%, 12주 45%를 목표로 합니다[가정]. 지표: 세션 완료율(벨트 체결로 자동 종료된 비율), 운전자 수동 지정 비율, 접근 알림부터 승차까지 걸린 시간. 방학에는 역·학원 외 '퇴근 마중' 시나리오로 이탈을 줄입니다.")
        + heading("다. 수익 모델과 확장 로드맵")
        + bullet("1단계(출시): 기본 픽업·안심 확인 무상 — 재사용 습관 검증.")
        + bullet("2단계: 다자녀 학원 일정 연동, 키즈폰 SMS 알림 등 통신 비용 발생 기능을 선택형 구독(월 2,900원 [가정])으로.")
        + bullet("3단계: 현대 카페이 제휴 주차장의 단기 무료 회차 구역 안내 연계로 학원가 불법 주정차 부담 완화.")
        + bullet("4단계(B2B): 학원 셔틀·어린이집 승하차 확인 — 벨트 체결·문 닫힘 로그로 '안전 승하차 확인서' 자동 생성.")
        + bullet("글로벌: 미국 초등학교 'car line' 픽업(2027년 Tucson부터 Pleos Connect 적용) — 번호판·정차 신호 기반 흐름은 언어와 무관하게 동일하게 동작합니다.")
    )

    ref = (
        bullet(f"**GitHub 저장소(소스·APK):** {GITHUB} — 2026.11.16~11.23 Public 유지. 최종 커밋 해시는 제출 이메일 본문에 기재.")
        + bullet("**릴리스 APK:** apk/ 폴더 — 마중_마중_release.apk (공식 규칙 팀명_앱이름; Pleos Connect 에뮬레이터 v2.1.0 / API 34 대상, Vehicle 2.3.2 실연동 빌드, majung-v2-pleos-release.apk 와 동일), majung-v2-sim-release.apk (일반 AVD·AAOS 에뮬레이터 시연용, 서버 없이 이 APK 하나로 픽업 시작·동승자 제안·수락·맞이·탑승 종료까지 체험 가능). 두 APK 모두 debug 키 서명으로 바로 설치됩니다.")
        + bullet("**시연 영상:** docs/demo/majung_demo.mp4 (이메일 첨부 동일 파일, 약 2분) — Pleos Connect v2.1.0 전용 에뮬레이터에서 pleos 빌드 실녹화(실제 Vehicle SDK 콜백·제어, 동승자 위치, 동승자가 지도에서 자리를 골라 보낸 '여기서 탈게요'와 수락 후 승차 지점 이동, 여기 못 서요 재경로, 낮/밤 전환) + 동승자 웹 실시간 중계 상태 동기 재생 . 화면 글자는 단계 이름 한 줄만 두고 설명은 내레이션(남성 음성)이 맡습니다. 차량 위치만 adb 훅으로 주입(디버그 빌드).")
        + bullet("**Pleos 에뮬레이터 검증 기록:** docs/verification/pleos_emulator_evidence.md (SDK 등록·콜백·제어 onComplete·능력 조회 로그, 에뮬레이터 제약과 대응), 화면 docs/screenshots/pleos_emulator_*.png")
        + bullet("**검증 방법(저장소에서 재현):** node relay-server/verify_e2e_sse.mjs · ./gradlew :app:testSimDebugUnitTest (56건) · ./scripts/verify_and_build_apk.sh")
        + bullet("**디자인·심볼:** docs/design/ (화면 시안 3종 비교와 채택안), docs/brand/majung-symbol.svg (심볼 '파란 판에 새긴 ㅈ' — 런처 아이콘·동승자 웹 헤더에 동일 벡터). 화면은 Pleos Connect 런처의 디자인(밝은 바탕·검은 판·라운드 12·얇은 선·시스템 서체)을 따르고 하향등이 켜지면 야간 색상으로 바뀝니다.")
    )
    if ivi_png and ivi_png.exists():
        ref += images.add(ivi_png, 16.0, "그림 1. Pleos Connect v2.1.0 에뮬레이터(런처 오른쪽 앱 영역)에서 실행 중인 pleos 빌드 — 지도 위 동승자(먹색 점)·내 차(파란 화살표)·승차 지점(현대 엠블럼), 상태 판, [여기 못 서요] 버튼.")
    if suggestion_png and suggestion_png.exists():
        ref += images.add(suggestion_png, 16.0, "그림 2. 동승자가 지도에서 고른 자리가 차량 지도에 마중 심볼로 표시되고, \"여기서 탈게요\" 제안이 카드로 도착한 화면(승차 지점에서 57 m). [수락]하면 승차 지점이 그 자리로 옮겨지고 내비가 재경로한다.")
    if web_png and web_png.exists():
        ref += images.add(web_png, 7.0, "그림 3. 동승자 무설치 웹 — 지도(차·나·승차 지점), 번호판 끝 4자리, 간편 제안 세 개(여기서 탈게요는 지도에서 자리를 골라 보냄). 상태 블록은 차량 신호(P단 정차·문·벨트)로만 바뀐다.")
    if icon_png and icon_png.exists():
        ref += images.add(icon_png, 11.0, "그림 4. Pleos 런처 앱 목록의 마중 아이콘 — 심볼 '파란 판에 새긴 ㅈ'(ㅁㅈ 모노그램, 현대 블루 판 위 흰 ㅈ).")
    c['reference_link'] = ref

    c['shortform_link'] = body("미참여 (공란 제출)")
    return c


# ------------------------------------------------------------------ 01 참가신청서 (개인정보: 저장소 밖의 JSON으로만 받는다)
def set_paragraph_text(p: str, text: str) -> str:
    """문단의 run 들을 첫 run 의 서식을 유지한 한 run 으로 바꾼다 (밑줄 공란 → 값)."""
    m = re.search(r'<w:r\b[^>]*>(<w:rPr>.*?</w:rPr>)', p, flags=re.S)
    rpr = m.group(1) if m else ''
    body = re.sub(r'<w:proofErr[^>]*/>', '', p)
    body = re.sub(r'<w:r\b.*?</w:r>', '', body, flags=re.S)
    return body.replace('</w:p>', f'<w:r>{rpr}<w:t xml:space="preserve">{escape(text)}</w:t></w:r></w:p>')


def fill_table_cells(xml: str, header_marker: str, row_index: int, values: dict, size: int | None = 16) -> str:
    """헤더에 header_marker 가 있는 표의 row_index 행에서 {열 번호: 값} 을 채운다."""
    def rep(m):
        tbl = m.group(0)
        rows = re.findall(r'<w:tr\b.*?</w:tr>', tbl, flags=re.S)
        if not rows or header_marker not in ''.join(re.findall(r'<w:t[^>]*>(.*?)</w:t>', rows[0], flags=re.S)):
            return tbl
        row = rows[row_index]
        cells = re.findall(r'<w:tc>.*?</w:tc>', row, flags=re.S)
        new_row = row
        for ci, val in values.items():
            c = cells[ci]
            rpr = f'<w:rPr><w:sz w:val="{size}"/><w:szCs w:val="{size}"/></w:rPr>' if size else ''
            run = f'<w:r>{rpr}<w:t xml:space="preserve">{escape(val)}</w:t></w:r>'
            if re.search(r'<w:p\b[^>]*/>', c):
                nc = re.sub(r'<w:p\b([^>]*)/>', lambda r: '<w:p' + r.group(1) + '>' + run + '</w:p>', c, count=1)
            else:
                nc = c.replace('</w:p>', run + '</w:p>', 1)
            new_row = new_row.replace(c, nc, 1)
        return tbl.replace(row, new_row, 1)
    return re.sub(r'<w:tbl>.*?</w:tbl>', rep, xml, flags=re.S)


def fill_personal(xml: str, info: dict) -> str:
    """01 참가신청서·04 서명표의 공란을 info 로 채운다. info 는 저장소 밖 JSON (성명·연락처는 저장소에 넣지 않는다).
    키: unit(개인|팀), division(대학(원)생|일반), rep_name, rep_role, members_count, date(YYYY-MM-DD),
        rep_affiliation, rep_email, rep_phone, members[{name, affiliation, phone, email, role}]"""
    def para_sub(prefix, new_text):
        nonlocal xml
        def rep(m):
            p = m.group(0)
            text = ''.join(re.findall(r'<w:t[^>]*>(.*?)</w:t>', p, flags=re.S))
            return set_paragraph_text(p, new_text) if text.strip().startswith(prefix) else p
        xml = re.sub(r'<w:p\b(?:(?!<w:p\b).)*?</w:p>', rep, xml, flags=re.S)
    box = lambda on: '■' if on else '□'
    if info.get('unit'):
        para_sub('참가 단위:', f"참가 단위: {box(info['unit'] == '개인')} 개인           {box(info['unit'] == '팀')} 팀")
    if info.get('division'):
        para_sub('참가 부문:', f"참가 부문: {box('대학' in info['division'])} 대학(원)생    {box('일반' in info['division'])} 일반(스타트업 포함)")
    if info.get('rep_name'):
        para_sub('대표자 성명:', f"대표자 성명: {info['rep_name']}    역할: {info.get('rep_role', '')}")
    if info.get('rep_affiliation'):
        para_sub('대표자 소속', f"대표자 소속(학교/학과/회사명/무소속 등): {info['rep_affiliation']}")
    if info.get('rep_email'):
        para_sub('대표자 이메일:', f"대표자 이메일: {info['rep_email']}")
    if info.get('rep_phone'):
        para_sub('대표자 휴대폰번호', f"대표자 휴대폰번호: {info['rep_phone']}")
    if info.get('members_count'):
        para_sub('참가 인원:', f"참가 인원: {info['members_count']}명")
    if info.get('date'):
        y, mo, d = info['date'].split('-')
        para_sub('작성일:', f"작성일: {int(y)}년 {int(mo)}월 {int(d)}일")
    for i, mem in enumerate(info.get('members', [])[:5]):
        vals = {0: mem.get('name', ''), 1: mem.get('affiliation', ''), 2: mem.get('phone', ''), 3: mem.get('email', ''), 4: mem.get('role', '')}
        xml = fill_table_cells(xml, '담당 역할', 1 + i, {k: v for k, v in vals.items() if v})
        xml = fill_table_cells(xml, '서명일자', 1 + i, {1: mem.get('name', '')}, size=None)
    return xml


# ------------------------------------------------------------------ 적용
def fill(official: Path, out: Path, ivi_png: Path | None, web_png: Path | None, icon_png: Path | None = None, personal: dict | None = None, suggestion_png: Path | None = None):
    tmp = Path(tempfile.mkdtemp(prefix='majung-form-'))
    with zipfile.ZipFile(official) as z:
        z.extractall(tmp)
    doc_path = tmp / 'word' / 'document.xml'
    xml = doc_path.read_text(encoding='utf-8')
    images = Images(tmp)
    blocks = content(images, ivi_png, web_png, icon_png, suggestion_png)

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
        p = re.sub(r'_{5,}', '마중 (MAJUNG) · 차량 신호로 확인하는 안심 픽업', p, count=1)
        return re.sub(r'_{2,}', '', p)  # run이 쪼개져 남은 밑줄 제거
    xml = re.sub(r'<w:p\b(?:(?!<w:p\b).)*?</w:p>', fill_app_name, xml, flags=re.S)
    print(f'app-name fields filled={filled}')

    # 3) 01 참가신청서의 팀명 공란: 팀명은 서비스명 "마중" (개인정보가 아니므로 채운다; 나머지 01 항목은 참가자가 기입)
    team_filled = 0
    def fill_team_name(m):
        nonlocal team_filled
        p = m.group(0)
        text = ''.join(re.findall(r'<w:t[^>]*>(.*?)</w:t>', p, flags=re.S))
        if '팀명(개인 참가 시 성명)' not in text or '_____' not in text:
            return p
        team_filled += 1
        p = re.sub(r'_{5,}', TEAM_NAME, p, count=1)
        return re.sub(r'(<w:t[^>]*>)([^<]*?)(</w:t>)', lambda r: r.group(1) + r.group(2).replace('_', '') + r.group(3), p)  # run이 쪼개져 남은 밑줄까지 제거
    xml = re.sub(r'<w:p\b(?:(?!<w:p\b).)*?</w:p>', fill_team_name, xml, flags=re.S)
    print(f'team-name fields filled={team_filled}')

    # 4) (선택) 01 참가신청서·04 서명표 — 제출용 사본에만, 저장소 밖 JSON 으로
    if personal:
        xml = fill_personal(xml, personal)
        print('personal fields filled (01/04) from', personal.get('_source', 'json'))

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
    icon = Path(a[a.index('--icon') + 1]) if '--icon' in a else None
    sugg = Path(a[a.index('--suggestion') + 1]) if '--suggestion' in a else None
    personal = None
    if '--personal' in a:
        import json
        pj = Path(a[a.index('--personal') + 1])
        personal = json.loads(pj.read_text(encoding='utf-8')); personal['_source'] = pj.name
    fill(Path(a[0]), Path(a[1]), ivi, web, icon, personal, sugg)
