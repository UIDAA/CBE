# CBE

온보딩 서비스의 업무 백엔드입니다. Kotlin + Spring Boot + Spring Modulith 단일 애플리케이션이며, 현재 조직·계정·알림·일정·투두·면담·대시보드 모듈이 들어 있습니다.

## 필요한 것

- JDK 25
- Docker (로컬 DB와 테스트용 PostgreSQL 컨테이너)

Gradle은 설치하지 않아도 됩니다. 저장소의 `./gradlew`를 씁니다.

## 실행

```bash
docker compose up -d
```

```bash
./gradlew bootRun
```

- 서버: `http://localhost:8080`
- API 문서(Swagger): `http://localhost:8080/swagger-ui.html`
- 8080 포트가 이미 사용 중이면 `PORT=8090 ./gradlew bootRun`처럼 포트를 바꿉니다.
- DB 테이블은 서버가 뜰 때 Flyway가 만듭니다.

## 테스트

```bash
./gradlew test
```

Testcontainers가 PostgreSQL 컨테이너를 직접 띄우므로 Docker만 실행 중이면 됩니다. `docker compose`로 띄운 DB는 쓰지 않습니다.

## 환경변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `PORT` | `8080` | 서버 포트 |
| `DB_URL` | `jdbc:postgresql://localhost:5432/onboarding` | DB 주소 |
| `DB_USERNAME` | `onboarding` | DB 계정 |
| `DB_PASSWORD` | `onboarding` | DB 비밀번호 |
| `JWT_SECRET` | 로컬 개발용 값 | JWT 서명 키. 32바이트 이상 |

기본값은 로컬 개발용입니다. 배포 환경에서는 `JWT_SECRET`과 `DB_PASSWORD`를 반드시 따로 넣어야 합니다.

## 모듈

`src/main/kotlin/com/onboarding/backend` 아래 패키지 하나가 모듈 하나입니다.

| 모듈 | API | 내용 |
|---|---|---|
| `account` | AUTH-001~005, USER-001~003 | 회사 가입, 초대 코드 가입, 로그인·로그아웃·토큰 갱신, 프로필·비밀번호 |
| `organization` | DEPT-001~004, INV-001~003, EMP-001~005 | 부서, 초대 코드, 직원, 신입–멘토 매칭 |
| `notification` | NOTI-001~003, USER-004~005 | 알림 목록·읽음 처리, 알림 설정 |
| `schedule` | SCH-001~004 | 개인·회사 일정, 반복 일정 |
| `todo` | TODO-001~004 | 개인 투두 |
| `interview` | INT-001~005 | 면담 신청·승인·거절 |
| `dashboard` | DASH-001~003 | HR·멘토·신입 홈 |
| `common` | — | 응답 형식, 오류 처리, 인증 주체, 알림 이벤트 |

`ModularityTest`가 모듈 간 순환 의존을 검사합니다. 새 모듈은 같은 위치에 패키지를 추가하면 됩니다.

## API 공통 규칙

- 경로는 `/api/v1`로 시작하고, 요청·응답 필드는 스네이크 케이스입니다.
- 성공 응답은 `{"data": {...}}` 형태입니다.
- 오류 응답은 `{"status": 409, "title": "...", "detail": "..."}` 형태(ProblemDetail)입니다.
- 목록의 `page`는 1부터 시작하고 `size` 기본값은 20입니다.
- 가입·로그인·토큰 갱신을 뺀 모든 API는 `Authorization: Bearer <access_token>` 헤더가 필요합니다.
- 다른 회사 데이터는 404, 같은 회사에서 권한이 없으면 403입니다.

## 다른 모듈과 연결하는 방법

### 알림 보내기

어느 모듈에서든 `NotifyEvent`를 발행하면 알림 모듈이 저장합니다. 행위자 본인은 수신자에서 자동으로 빠지고, `mandatory = false`이면 수신자의 알림 설정을 따릅니다.

```kotlin
events.publishEvent(
    NotifyEvent(
        companyId = me.companyId,
        actorId = me.id,
        recipientIds = listOf(newbieId),
        category = NotificationCategory.TASK,
        mandatory = false,
        title = "과제가 승인되었습니다.",
        linkType = "task",
        linkId = taskId,
    ),
)
```

### 조직 정보 읽기

직원·부서·담당 멘토 정보는 `OrganizationDirectory`로 조회합니다.

### 임시 구현을 실제 구현으로 바꾸기

아래 인터페이스는 지금 임시 구현이 들어 있습니다. 담당 모듈에서 구현체를 만들고 임시 구현 클래스를 지우면 됩니다.

| 인터페이스 | 임시 구현 | 현재 동작 | 채워야 하는 모듈 |
|---|---|---|---|
| `OnboardingMetricsPort` | `EmptyOnboardingMetrics` | 대시보드 지표가 비어 있고 신입 홈은 404 | 온보딩·과제·일지·퀴즈·설문·도움 요청 |
| `OrganizationPolicyPort` | `TemporaryOrganizationPolicy` | 인원 한도 항상 통과, 진행 중 배정 항상 없음 | 요금제, 온보딩 배정 |
| `InviteMailSender` | `LoggingInviteMailSender` | 로그만 남김 | 메일 연동 |

## 아직 없는 것

기능명세서에 기준이 정해지지 않아 만들지 않았습니다.

- 로그인 실패 계정 잠금
- 비밀번호 문자 조합 규칙 (길이 8~20자만 검사)
- 공휴일 처리 (`holiday_policy` 값은 저장만 함)
- 캘린더의 과제·퀴즈 기한 자동 표시
- 최근 활동 기록
