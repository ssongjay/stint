# Stint

맥과 안드로이드에서 같은 집중 타이머와 기록을 이어 보는 개인용 앱.
크림색·잉크색의 차분한 워크벤치로 현재 작업과 시간을 먼저 보여준다.
가칭은 기존 이름 후보 중 Stint를 사용한다.

## 구성

| 모듈 | 역할 |
|---|---|
| composeApp | Compose Multiplatform 화면, macOS 앱, Android 앱, 로컬 캐시 |
| shared | JSON 계약, 타이머 계산, 클라이언트 인터페이스 |
| server | Kotlin Ktor API·WebSocket, MySQL 트랜잭션과 기록 |
| scripts | 독립 개발 DB와 실행·검증 도구 |

기기들은 한 서버의 현재 상태를 공유한다. iCloud는 사용하지 않는다.
서버의 revision과 명령 id로 동시 조작·재전송을 처리하고, 연결이 끊기면
저장된 타이머와 기록을 보여준다. 오프라인 조작은 재연결 후에 허용한다.

## 개발 환경

- JDK **17**. Android Studio의 번들 JDK 25 대신 Gradle JDK도 17로 지정한다.
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

개발용 연결 정보는 .local/dev.json에 만들고 Git에서 제외한다. 앱에 키를
하드코딩하지 않는다. Android 앱은 연결 화면에서 서버 URL과 키를 입력한다.
실제 서비스에서는 HTTPS 주소를 사용한다.

## 검증과 패키징

```sh
./gradlew :shared:jvmTest
python3 scripts/dev.py test
./gradlew :composeApp:desktopTest :composeApp:lintDebug :composeApp:assembleDebug
./gradlew :composeApp:createDistributable
```

scripts/dev.py test는 독립된 stint_test DB를 사용한다. 운영 DB를 테스트에
연결하지 않는다. 물리 Android 기기 검증은 빌드·에뮬레이터 검증과 구분한다.

## 설계

- [설치와 두 기기 연결](docs/INSTALL.md)
- [현재 구현 기준](docs/IMPLEMENTATION.md)

이전 Android 학습 전용·서버 없음 계획은 2026-10-29 요청으로 교체했다.
참고 앱의 사운드·이미지·문구·화면 배치를 가져오지 않는다.
