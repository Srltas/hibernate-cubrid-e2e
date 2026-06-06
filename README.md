# hibernate-cubrid-e2e

External end-to-end tests that exercise `CUBRIDDialect` against a **real CUBRID** server,
booted with [testcontainers-cubrid][tc-cubrid] and driven through the Hibernate ORM JPA API.

This suite is the verification evidence for the `CUBRIDDialect` modernization
([HHH-20527][jira]). CUBRID is not part of Hibernate's CI matrix, so the dialect changes are
validated here against CUBRID 10.2 – 11.4 instead. It consumes Hibernate `8.0.0-SNAPSHOT` from
mavenLocal, so it always tests the dialect you have locally.

> While building this suite we found and fixed two mis-declared capability flags
> (`supportsAlterColumnType` was missing its `getAlterColumnTypeString` companion;
> `supportsJoinsInDelete=true` produced broken SQL because the CUBRID translator does not render
> joins-in-delete). See [What each test actually verifies](#what-each-test-actually-verifies).

[tc-cubrid]: https://github.com/cubrid/testcontainers-cubrid
[hibernate-orm]: https://github.com/hibernate/hibernate-orm
[jira]: https://hibernate.atlassian.net/browse/HHH-20527

---

## Prerequisites

- Java 17+
- A running Docker (or Docker Desktop)
- A local `hibernate-orm` checkout containing the `CUBRIDDialect` changes you want to verify

---

## How it works

The two projects are linked by the version string `8.0.0-SNAPSHOT`. The e2e `build.gradle`
resolves `mavenLocal()` first, so the locally published JARs win.

```
hibernate-orm  ──(publishToMavenLocal)──▶  ~/.m2  ──(8.0.0-SNAPSHOT)──▶  hibernate-cubrid-e2e
                                                                              │
                                                          testcontainers boots cubrid/cubrid:<ver>
                                                                              │
                                                          42 tests drive the dialect via JPA/HQL
```

### One-time — publish the four Hibernate modules

```sh
cd /path/to/hibernate-orm
./gradlew :hibernate-core:publishToMavenLocal \
          :hibernate-community-dialects:publishToMavenLocal \
          :hibernate-platform:publishToMavenLocal \
          :hibernate-testing:publishToMavenLocal \
          -x test -x javadoc
```

### After editing `CUBRIDDialect.java` — re-publish just that module, then re-run

```sh
cd /path/to/hibernate-orm
./gradlew :hibernate-community-dialects:publishToMavenLocal -x test -x javadoc

cd /path/to/hibernate-cubrid-e2e
./gradlew testCubrid10_2 --refresh-dependencies --rerun-tasks
```

`--refresh-dependencies --rerun-tasks` forces Gradle to re-resolve the SNAPSHOT and re-run the tests.

---

## Choosing the CUBRID version

Default is `10.2` (the modernized dialect's minimum supported version).

| Goal                         | Command                                       |
| ---------------------------- | --------------------------------------------- |
| CUBRID 10.2 (default)        | `./gradlew testCubrid10_2`                    |
| A specific version           | `./gradlew testCubrid11_4`                    |
| All supported versions       | `./gradlew testCubridAll`                     |
| A single test                | `./gradlew testCubrid10_2 --tests "*pessimisticWrite*"` |

Named tasks: `testCubrid10_2`, `testCubrid11_0`, `testCubrid11_2`, `testCubrid11_3`, `testCubrid11_4`.

---

## What each test actually verifies

42 tests across four classes. Crucially, **not every test is a flag verifier** — a test only
*verifies* a dialect change if it would **fail when that change is reverted**. We checked this by
reverting each changed flag and re-running (a mutation test). The tests fall into three honest tiers:

### Tier 1 — primary, mutation-guarded verification (`CubridCapabilityIT`, 26 tests)

A `@ParameterizedTest` reflects the dialect's declared value for each SQL-feature flag, fires a
minimal probe SQL straight at CUBRID, and asserts **declared value == CUBRID acceptance**. By
construction this fails the moment a flag is mis-declared, so it is the suite's strongest evidence.
It genuinely verifies these PR-changed flags:
`supportsWindowFunctions`, `supportsRecursiveCTE`, `supportsNestedWithClause`, `supportsIsTrue`,
`supportsValuesList`, `supportsNonQueryWithCTE`, and all five row-value-constructor flags.

Plus three dedicated checks in the same class:
- `alterColumnTypeClauseAcceptedByCubrid` — builds the `ALTER TABLE … MODIFY COLUMN` clause the
  dialect emits and fires it at CUBRID (regression guard for the `getAlterColumnTypeString` fix).
- `maxIdentifierLengthAcceptedByCubrid` — a 254-byte identifier (Hibernate's truncation target) is
  accepted by CUBRID.
- `defaultConstructorUsesMinimumVersion` — the no-arg constructor defaults to CUBRID 10.2.

### Tier 2 — behaviour verified through Hibernate, fails on revert (`CubridQueryFeatureIT`, `CubridLockingIT`)

- `explicitNullsLastOnAscending`, `explicitNullsFirstOnDescending` — verify `getNullOrdering=SMALLEST`
  (assert both row order and that the emitted SQL contains `nulls last`/`nulls first`; revert to the
  base `GREATEST` and they fail).
- `recursiveCteWalksTree` — verifies `supportsRecursiveCTE` (reverting throws at translation).
- `pessimisticWriteAddsForUpdate`, `pessimisticReadAlsoAddsForUpdate` — verify the locking fix
  (`FOR UPDATE` is emitted; the pre-PR empty `getForUpdateString()` produced none).
- `leftJoinWithPessimisticWriteSucceeds` — outer join + `FOR UPDATE` (`OuterJoinLockingType.FULL`).

### Tier 3 — native-execution / regression checks (NOT flag verifiers)

These confirm a feature runs correctly on CUBRID but would still pass if the named flag were
reverted (the flag is either a no-op override, or the SELECT path does not consult it). They are
useful smoke/regression coverage, not proof of a flag:
- `windowFunctionRowNumberOverPartitionBy`, `topLevelWithClauseFiltersResults`,
  `unionOfGroupedQueriesWithOuterOrderBy`, `bulkDeleteWithSubqueryFilter`,
  `limitOffsetUsesCubridLimitClause`, and the two plain (`asc`/`desc`) null-ordering tests, which
  verify CUBRID's *native* null ordering rather than the dialect flag.
- `CubridIdGenerationIT` (3) — IDENTITY and SEQUENCE/`.next_value`; pre-existing behaviour, unchanged
  by this PR, kept as regression coverage.

All tests assert results and, where it matters, the emitted SQL via Hibernate's
[`SQLStatementInspector`][inspector].

### Not covered here (documented gaps / limitations)

- `supportsFromClauseInUpdate=false`, `canCreateSchema=false`, `supportsTableCheck=false`,
  `supportsCommentOn=false` — DDL/edge-of-harness concerns, asserted by reasoning + the CUBRID 10.2
  manual rather than a runtime probe.
- Implicit-association bulk delete (`delete … where p.assoc.attr = …`) is **unsupported on CUBRID via
  Hibernate**: the join-emulation correlates the outer FK into a subquery JOIN condition, which CUBRID
  rejects (`'…' in join condition is not defined`). Use the FK-direct subquery form instead.

[inspector]: https://github.com/hibernate/hibernate-orm/blob/main/hibernate-testing/src/main/java/org/hibernate/testing/jdbc/SQLStatementInspector.java

---

## Project layout

```
src/test/
├── resources/META-INF/persistence.xml
└── java/org/cubrid/hibernate/e2e/
    ├── entity/
    │   ├── Member.java       (IDENTITY id)
    │   ├── SalesOrder.java   (sequence id; "SalesOrder" because ORDER is reserved)
    │   ├── Game.java         (parent of 1:N)
    │   ├── Player.java       (child)
    │   └── OrgUnit.java      (self-referential tree, for the recursive CTE)
    ├── support/
    │   └── AbstractCubridIT.java   (container + EntityManagerFactory + SQLStatementInspector)
    ├── CubridCapabilityIT.java     (Tier 1)
    ├── CubridQueryFeatureIT.java   (Tiers 2/3)
    ├── CubridLockingIT.java        (Tier 2)
    └── CubridIdGenerationIT.java   (Tier 3)
```

---

## Troubleshooting

- **`8.0.0-SNAPSHOT` not found** — re-run the publish step. `hibernate-platform` is consumed
  transitively as a BOM, so it must be published too.
- **Tests pass but seem to use old code** — add `--refresh-dependencies --rerun-tasks` so Gradle
  reloads the SNAPSHOT JAR.
- **Docker unresponsive after repeated runs (Apple Silicon)** — the CUBRID image is amd64 and runs
  under Rosetta; restart Docker Desktop or raise its memory.
- **Hibernate version changed** (e.g. `8.1.0-SNAPSHOT`) — update `hibernateVersion` in `build.gradle`
  and re-publish.
