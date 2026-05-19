# hibernate-cubrid-e2e

`CUBRIDDialect`의 동작을 실제 CUBRID 인스턴스로 검증하는 통합 테스트 프로젝트.
[testcontainers-cubrid][tc-cubrid]로 컨테이너를 띄우고 Hibernate ORM의 JPA API를
통해 dialect를 직접 사용해본다. [hibernate-orm][hibernate-orm]에 PR을 올리기 전
로컬에서 변경사항을 검증하는 용도.

[tc-cubrid]: https://github.com/cubrid/testcontainers-cubrid
[hibernate-orm]: https://github.com/hibernate/hibernate-orm

---

## 사전 요구사항

- Java 17 이상
- 로컬에서 동작 중인 Docker (또는 Docker Desktop)
- `hibernate-orm` 로컬 체크아웃 (검증하고 싶은 `CUBRIDDialect` 변경이 들어있는 상태)

---

## 동작 원리

```
┌─────────────────────────────┐                ┌─────────────────────────────┐
│  hibernate-orm (수정본)     │                │  hibernate-cubrid-e2e       │
└──────────────┬──────────────┘                └──────────────┬──────────────┘
               │                                              │
               │ ./gradlew publishToMavenLocal                │ ./gradlew test
               ▼                                              ▼
         ~/.m2/repository  ── 8.0.0-SNAPSHOT 아티팩트 ──→  mavenLocal()로 가져옴
                                                              │
                                                              ▼
                                                  testcontainers가
                                                  cubrid/cubrid:<버전> 컨테이너 실행
                                                              │
                                                              ▼
                                                  12개 테스트가 dialect 동작 검증
```

두 프로젝트는 **버전 문자열** `8.0.0-SNAPSHOT`으로 연결된다.
e2e의 `build.gradle`이 `mavenLocal()`을 먼저 검색하므로 로컬 publish한 JAR이 우선 사용된다.

---

## 빠른 시작

### 최초 1회 — Hibernate의 필요한 4개 모듈 publish

```sh
cd /path/to/hibernate-orm
./gradlew :hibernate-core:publishToMavenLocal \
          :hibernate-community-dialects:publishToMavenLocal \
          :hibernate-platform:publishToMavenLocal \
          :hibernate-testing:publishToMavenLocal \
          -x test -x javadoc
```

### 이후 작업 — 수정한 모듈만 다시 publish

`CUBRIDDialect.java`를 수정했다면:

```sh
cd /path/to/hibernate-orm
./gradlew :hibernate-community-dialects:publishToMavenLocal -x test
```

다른 모듈을 수정했다면 해당 모듈을 publish.

### 테스트 실행

```sh
cd /path/to/hibernate-cubrid-e2e
./gradlew test --rerun-tasks
```

`--rerun-tasks`는 Gradle 캐시를 무시하고 새로 publish된 JAR을 다시 로드한다.

---

## CUBRID 버전 선택

기본값은 `10.2` (modernize된 dialect의 최소 지원 버전).
`cubridVersion` Gradle 프로퍼티 또는 명명된 태스크로 변경 가능.

| 목적                          | 명령                                                      |
| ----------------------------- | --------------------------------------------------------- |
| CUBRID 10.2 (기본)            | `./gradlew test`                                          |
| CUBRID 11.4                   | `./gradlew test -PcubridVersion=11.4`                     |
| 특정 버전 (명명 태스크)        | `./gradlew testCubrid11_4`                                |
| 지원 버전 모두 순차 실행      | `./gradlew testCubridAll`                                 |
| 특정 테스트만                 | `./gradlew test --tests "*pessimisticWrite*"`             |

사용 가능한 명명 태스크: `testCubrid10_2`, `testCubrid11_0`, `testCubrid11_2`,
`testCubrid11_3`, `testCubrid11_4`.

---

## 검증 항목

테스트 클래스 하나(`CubridDialectIT`)에 `@Nested`로 그룹화된 12개의 테스트 메서드:

| 그룹                  | 개수 | 검증 내용                                                                |
| --------------------- | ---- | ----------------------------------------------------------------------- |
| **Pessimistic locking** | 3 | `PESSIMISTIC_WRITE`/`PESSIMISTIC_READ`에 `FOR UPDATE` 절 포함, outer join과의 조합 |
| **Query feature support** | 6 | window 함수(`OVER`), CTE(`WITH`), 서브쿼리 기반 bulk DELETE, `LIMIT n,m` 페이지네이션, grouped `UNION` + outer `ORDER BY`, `NULLS FIRST` 기본 동작 |
| **ID generation**       | 3 | `AUTO_INCREMENT` 왕복, sequence ID 증가, INSERT 시 native `.next_value` 구문 |

각 테스트는 실행 결과(query 반환값)와 생성된 SQL 형태(Hibernate의 [`SQLStatementInspector`][inspector] 사용)를
둘 다 검증한다. 따라서 *"`FOR UPDATE`가 실제로 SQL에 들어간다"* 같은 PR 주장이
실제로 CUBRID에 보내지는 SQL 수준에서 입증된다.

[inspector]: https://github.com/hibernate/hibernate-orm/blob/main/hibernate-testing/src/main/java/org/hibernate/testing/jdbc/SQLStatementInspector.java

---

## 프로젝트 구조

```
hibernate-cubrid-e2e/
├── build.gradle
├── settings.gradle
├── gradle.properties
└── src/test/
    ├── resources/META-INF/
    │   └── persistence.xml
    └── java/org/cubrid/hibernate/e2e/
        ├── entity/
        │   ├── Member.java         (IDENTITY id)
        │   ├── SalesOrder.java     (sequence id)
        │   ├── Game.java           (1:N의 부모)
        │   └── Player.java         (자식)
        ├── support/
        │   └── AbstractCubridIT.java
        └── CubridDialectIT.java
```

---

## 문제 해결

- **`8.0.0-SNAPSHOT`을 못 찾는다**: publish 단계를 다시 실행. `hibernate-platform`은
  직접 참조하지 않지만 BOM으로 transitively 소비되므로 필수.
- **테스트는 통과하지만 옛 코드가 사용되는 것 같다**: `--rerun-tasks`를 붙여
  Gradle이 SNAPSHOT JAR을 강제로 다시 로드하게 한다.
- **반복 실행 후 Docker가 응답 없음 (Apple Silicon)**: CUBRID 이미지가 amd64라서
  Rosetta 에뮬레이션으로 동작한다. Docker Desktop을 재시작하거나 할당 메모리를
  늘리면 해결된다.
- **Hibernate 버전이 바뀐 경우** (예: `8.1.0-SNAPSHOT`): 이 프로젝트의
  `build.gradle`의 `hibernateVersion`을 동일 문자열로 수정한 뒤 publish 재실행.
