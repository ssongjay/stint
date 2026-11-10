# Stint

macOS와 Android에서 같은 집중 타이머와 기록을 이어 보는 Kotlin 앱이다.
Compose Multiplatform 화면과 Ktor/MySQL 서버를 사용하며 iCloud에 의존하지 않는다.

## 기능

- 주제별 집중 시간, 짧은·긴 휴식, 긴 휴식 간격
- 카운트다운·스톱워치, 일시정지·재개·종료
- 오빗 링·모래시계 표시와 기기별 시계 선택 저장
- 멈춘 카운트다운의 경과 시간을 유지하는 목표 시간 변경
- 완료한 집중 기록, 회고, 오늘·7일·30일 통계
- HTTPS snapshot과 WebSocket을 통한 여러 기기 동기화
- 접힘·펼침·테이블탑 자세에 맞춘 Android 폴더블 레이아웃
- macOS 메뉴바의 진행 링·남은 시간 표시와 일시정지·재개·종료
- Android 실시간 타이머 칩·Now Bar 표시 요청, 알림 제어와 백그라운드 동기화
- 집중·휴식 종료 시스템 알림과 알림 테스트

## 구성

| 모듈 | 역할 |
|---|---|
| composeApp | Compose Multiplatform 화면, macOS 앱, Android 앱, 로컬 캐시 |
| shared | JSON 계약, 타이머 계산, 클라이언트 인터페이스 |
| server | Kotlin Ktor API·WebSocket, MySQL 트랜잭션과 기록 |
| scripts | 격리된 개발 DB와 검증 도구 |

기기들은 한 서버의 현재 상태를 공유한다. iCloud는 사용하지 않는다.
서버의 revision과 명령 id로 동시 조작·재전송을 처리하고, 연결이 끊기면
저장된 타이머와 기록을 보여준다. 오프라인 조작은 재연결 후에 허용한다.

## 코드 읽는 순서

`집중 시작`이 다른 기기에 반영되는 흐름부터 따라가면 각 모듈의 책임을 볼 수 있다.

1. `shared/.../Models.kt`, `ClientContract.kt`: 주제·타이머·기록의 형태와 화면의 명령 계약.
2. `composeApp/src/commonMain/.../ui/FocusTab.kt`: 버튼이 컨트롤러에 전달하는 사용자 의도.
3. `composeApp/src/commonMain/.../client/StintController.kt`: 명령 전송, 상태 수신, 재연결과 캐시.
4. `server/.../Api.kt`, `Repository.kt`, `TimerEngine.kt`: 인증, 트랜잭션, 상태 전이와 변경 전달.
5. `composeApp/src/commonMain/.../ui/StintApp.kt`: 수신한 상태를 시간에 맞춰 화면에 표시.

그다음 시간 편집은 `TimeDial.kt`와 `TimeWheelDigits.kt`, 회고는 `MemoEditor.kt`와
`MemoHistory.kt`를 읽는다. Mac 메뉴 막대·알림·저장소는 `desktopMain`, Android의
접힘 자세·알림·업데이트·저장소는 `androidMain`에서 공통 코드와 만나는 지점을 찾는다.
실행 규칙은 단위 테스트, 저장 경계는 MySQL 통합 테스트, 터치·키보드는 계측 테스트와
함께 읽으면 각 코드가 보호하는 동작을 확인할 수 있다.

타이머 오른쪽에서 **오빗 링 / 모래시계**를 고를 수 있다. 한 바퀴와 모래 전체는
항상 60분 기준이며 숫자가 실제 남은 시간을 표시한다. 모양은 기기별로 저장하고
타이머·기록은 서버로 동기화한다.

시계 옆 **시간 설정**에서 기본 집중·휴식 시간과 간격을 바꾼다.
멈춘 카운트다운은 이미 지난 시간을 유지하면서 이번 전체 목표만 바꿀 수 있다.
실행 중이라면 먼저 일시정지한다. 이미 지난 시간 이하로 목표를 줄이지 않는다.

시계의 분·초 숫자를 각각 위아래로 밀어 시간을 맞춘다. 위로 밀면 늘어나고
아래로 밀면 줄어들며, 손을 떼면 분은 1분·초는 1초 눈금에 맞춰 한 번 저장한다.
Android는 눈금마다 가벼운 시스템 햅틱을 사용하고 Mac은 휠·트랙패드·방향키도 지원한다.
시작 전에는 기본 집중 시간, 멈춘 뒤에는 화면에 보이는 남은 시간을 바꾼다.
이미 지난 시간은 그대로 유지한다. 기존 설정 화면도 분·초를 따로 입력할 수 있다.

Mac 상단 메뉴 막대에는 현재 타이머의 남은 시간 또는 스톱워치 경과 시간이 표시된다.
숫자 옆 작은 링은 앱 시계와 같은 60분 기준이고, 일시정지는 두 줄, 휴식은 초승달로
구분한다. 클릭하면 주제·집중/휴식 상태·경과 시간과 제어 메뉴를 볼 수 있다.
창을 닫아도 표시가 유지되며, 연결이 끊기면 시간 대신 연결 상태를 표시한다.

## 개발 환경

- JDK **17**
- Gradle Wrapper 8.13 / Kotlin 2.2.21 / Compose Multiplatform 1.9.3.
- Android SDK 36, minSdk 26.
- MySQL 서버 바이너리. 개발용 인스턴스는 기존 MySQL과 별도로 3307에 실행한다.

```sh
python3 scripts/dev.py start
python3 scripts/dev.py server
```

별도 터미널에서:

```sh
python3 scripts/dev.py desktop
./gradlew :composeApp:assembleDebug
```

개발용 연결 정보는 `.local/dev.json`에 만들고 Git에서 제외한다. 운영 서버 주소와
연결 키는 소스나 APK에 넣지 않는다. 앱의 연결 화면에서 HTTPS 서버 주소와
충분히 긴 임의 연결 키를 입력한다.

## 검증과 패키징

```sh
./gradlew :shared:jvmTest
python3 scripts/dev.py test
./gradlew :composeApp:desktopTest :composeApp:lintDebug :composeApp:assembleDebug
./gradlew :composeApp:createDistributable
```

scripts/dev.py test는 독립된 stint_test DB를 사용한다. 운영 DB를 테스트에
연결하지 않는다. 물리 Android 기기 검증은 빌드·에뮬레이터 검증과 구분한다.

## 문서

- [설치와 두 기기 연결](docs/INSTALL.md)
- [구현과 동기화 규칙](docs/IMPLEMENTATION.md)
- [데이터 모델](docs/DATA_MODEL.md)

폰트 라이선스 원문은 `composeApp/src/commonMain/composeResources/files/`에 있다.
