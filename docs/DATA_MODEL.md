# 데이터와 동기화 모델

정의의 기준은 shared/src/commonMain/kotlin/dev/stint/shared/Models.kt와
server/src/main/kotlin/dev/stint/server/Database.kt다.

| 테이블 | 내용 |
|---|---|
| subjects | 주제와 집중·휴식 설정, 보관 여부 |
| app_state | 단일 사용자 현재 revision |
| timer_state | 단일 활성 타이머, 누적 milliseconds, 실행 기준 시각, 현재 단계 |
| focus_sessions | 완료한 집중 기록과 회고. PK는 원본 집중 timer id |
| processed_commands | 이미 적용한 command id |
| devices | 기기 이름과 최근 연결 시각 |
| recent_memos | 최근 저장 회고의 SHA-256·본문·사용 revision. 중복 없이 최대 5개 |

## 변경 처리

state 행을 잠근 뒤 receipt를 먼저 확인한다. 신규 명령일 때만 expectedRevision을
비교한다. 상태·기록·receipt·revision을 한 트랜잭션에서 확정한 뒤 알림을 보낸다.
기기 presence는 domain revision을 올리지 않는다. 조회는 일관된 snapshot을 사용한다.

focus 타이머가 만료되면 정확한 deadline으로 기록을 한 번 만든다. 휴식은 집중
통계에 넣지 않는다. 수동 종료는 실제 집중 초만 기록하며 1초 미만은 만들지 않는다.
동일 timer id의 기록은 PK에서도 중복되지 않는다. 타이머는 매초 저장하지 않는다.

회고를 저장할 때 최근 문구 이력도 같은 트랜잭션에서 갱신한다. 앞뒤 공백을
제거한 본문의 UTF-8 SHA-256으로 중복을 구분하고 마지막 저장 revision 순서로
최근 5개를 유지한다. 오래된 집중 기록을 수정해도 실제 저장 순서가 반영된다.
빈 회고는 추천에 추가하지 않으며 명령 재전송은 이력 순서를 다시 바꾸지 않는다.
기존 DB는 최초 이관 때 기록 종료 시각순으로 최대 5개를 가져온다.

주제의 `pomodoroEnabled`와 `autoStartBreak`는 서버에 저장되는 동기화 설정이다.
둘 다 기본값은 true이며 기존 DB에는 누락된 컬럼만 추가한다. 새 클라이언트는
`SaveSubjectRequest.includesFlowSettings=true`로 두 설정의 저장 의도를 표시한다.
이 필드가 없는 구버전 요청은 기존 주제를 편집해도 저장된 흐름 설정을 유지한다.

클라이언트는 같은 연결 identity에서 낮은 revision을 폐기한다. 서버 연결 변경과
accepted snapshot 저장은 같은 mutex에서 처리해 이전 서버 응답이 새 서버 캐시를
오염시키지 못하게 한다. UTC epoch milliseconds를 보관하고 표시·날짜 집계는
현재 기기의 현지 시간대를 사용한다.

## 비밀과 캐시

개인 연결 키는 JSON 직렬화 대상이 아니다. macOS Keychain, Android Keystore로
보호한다. 서버별 identity hash로 캐시를 구분하고 원자적인 파일 교체로 저장한다.
Android cloud backup/device transfer에서는 설정·캐시를 제외한다.

서버는 MySQL을 원본으로 삼고 앱 캐시는 백업을 대신하지 않는다. DB 백업은
연결 키와 분리해 암호화하고, 별도 환경에 복원 검증한 뒤 운영 절차로 사용한다.
