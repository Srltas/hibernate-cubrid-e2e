# CUBRID 10.2 — Hibernate `Dialect` Capability 감사(Audit)

> `org.hibernate.community.dialect.CUBRIDDialect` (최소 지원 버전 **10.2**) 레퍼런스.
> 2026-06-05 생성 · 2026-06-07 갱신. `org.hibernate.dialect.Dialect`의 모든 capability 메서드를 열거해 CUBRID 10.2 기준으로
> 분류했으며, CUBRID 10.2 매뉴얼 + 실제 CUBRID 10.2~11.4 e2e(`CubridCapabilityIT`의 `declared == CUBRID 수락` 프로브 + 기능 테스트)로 검증했습니다.
>
> **2026-06-07 갱신 내역:** ① row value constructor 5종을 실제 CUBRID 10.2 실행으로 재확정(`Syntax`/`InInList`=`true`, `GtLt`/`InInSubQuery`/`Quantified`=`false`). ② `supportsAlterColumnType`에 companion 메서드 `getAlterColumnTypeString` 추가(없으면 `alter table … null` 깨진 DDL 생성). ③ `supportsJoinsInDelete` override **제거** — CUBRIDSqlAstTranslator가 delete의 조인을 렌더하지 않아 `true`는 깨진 SQL을 만들므로 base `false`(서브쿼리 emulation)가 정확. (각 테스트가 실제로 무엇을 검증하는지는 README의 3-tier 모델 참조.)

**출처 범례:** 📖 CUBRID 10.2 매뉴얼 · 🧪 Testcontainers 프로브(실제 CUBRID 10.2) · 🔬 e2e 기능 테스트 · 🐬 MySQL과 동일(가장 가까운 친척) · ⚙️ 기존(이번 작업 전부터 존재)

## 요약

| Capability 영역 | 전체 | CUBRID override | 기본값 상속 |
|---|---:|---:|---:|
| no-arg `public boolean` 플래그 | 131 | 28 | 103 |
| 비-boolean capability 메서드(enum/한도) | 39 | 12 | 27 |

상속된 기본값은 대부분 이미 CUBRID에 적합하며, 아래 override 항목들이 기본값과 다른 것들입니다.

## 1. CUBRID 10.2가 override하는 capability

### 1a. Boolean 플래그

**CTE / WITH 절**

| 메서드 | CUBRID | 기본값 | 근거 | 출처 |
|---|---|---|---|---|
| `supportsNonQueryWithCTE` | `true` | `false` | CTE를 INSERT/UPDATE/DELETE에 사용 가능 | 📖 매뉴얼, 🧪 프로브 |
| `supportsRecursiveCTE` | `true` | `false` | WITH RECURSIVE 지원 | 📖 매뉴얼, 🧪 프로브 |
| `supportsWithClauseInSubquery` | `true` | `supportsWithClause()` | WITH 절을 서브쿼리/파생 테이블 안에서 사용 가능 | 🧪 프로브 |
| `supportsNestedWithClause` | `false` | `supportsWithClauseInSubquery()` | 다른 CTE 정의 안의 WITH는 거부됨("Nested WITH clauses are not supported") | 📖 매뉴얼, 🧪 프로브 |

**쿼리 · 윈도우 · 집합 연산**

| 메서드 | CUBRID | 기본값 | 근거 | 출처 |
|---|---|---|---|---|
| `supportsExistsInSelect` | `false` | `true` | SELECT 목록에서 EXISTS 사용 불가 | ⚙️ 기존 |
| `supportsOffsetInSubquery` | `true` | `false` | 서브쿼리 안에서 LIMIT offset 사용 가능 | ⚙️ 기존 |
| `supportsWindowFunctions` | `true` | `false` | 윈도우 함수(OVER) 네이티브 동작 | 📖 매뉴얼, 🔬 e2e |
| `supportsPartitionBy` | `true` | `false` | 윈도우 함수의 PARTITION BY | 🔬 e2e |
| `supportsValuesList` | `true` | `false` | VALUES (..),(..) 리스트 / 파생 테이블 지원 | 🧪 프로브 |
| `supportsSimpleQueryGrouping` | `true` | `true` | 그룹화된 집합 연산 쿼리 + 바깥 ORDER BY | 🔬 e2e |

**술어(predicate)**

| 메서드 | CUBRID | 기본값 | 근거 | 출처 |
|---|---|---|---|---|
| `supportsIsTrue` | `true` | `false` | IS TRUE / IS FALSE 술어 지원 | 🧪 프로브, 🐬 MySQL |
| `supportsTupleDistinctCounts` | `false` | `true` | 여러 컬럼 COUNT(DISTINCT a, b) 미지원 | ⚙️ 기존 |

**Row value constructor**

| 메서드 | CUBRID | 기본값 | 근거 | 출처 |
|---|---|---|---|---|
| `supportsRowValueConstructorSyntax` | `true` | `true` | 튜플 동등 `(a,b)=(c,d)` 수락 | 🧪 프로브 |
| `supportsRowValueConstructorSyntaxInInList` | `true` | `true` | 튜플 IN 리스트 `(a,b) IN ((..),(..))` 수락 | 🧪 프로브 |
| `supportsRowValueConstructorGtLtSyntax` | `false` | `supportsRowValueConstructorSyntax()` | 튜플 `<`/`>` 거부 — root에서 `true` 파생되므로 명시 고정 | 🧪 프로브 |
| `supportsRowValueConstructorSyntaxInInSubQuery` | `false` | `supportsRowValueConstructorSyntaxInInList()` | 튜플 `IN (subquery)` 거부 — InInList에서 `true` 파생되므로 명시 고정 | 🧪 프로브 |
| `supportsRowValueConstructorSyntaxInQuantifiedPredicates` | `false` | `true` | quantified 튜플 비교 거부 — base가 `true` 하드코딩이라 명시 고정 | 🧪 프로브 |

**DML (update/delete)**

| 메서드 | CUBRID | 기본값 | 근거 | 출처 |
|---|---|---|---|---|
| `supportsFromClauseInUpdate` | `false` | `false` | UPDATE에 다중 테이블 FROM 절 미지원 | 📖 매뉴얼 |

**DDL — 제약조건**

| 메서드 | CUBRID | 기본값 | 근거 | 출처 |
|---|---|---|---|---|
| `supportsAlterColumnType` | `true` | `false` | ALTER TABLE … MODIFY로 컬럼 타입 변경 — companion **`getAlterColumnTypeString`**(`"modify column …"`)도 함께 override 필수(없으면 `alter table … null`) | 📖 매뉴얼, 🔬 e2e |
| `supportsColumnCheck` | `false` | `true` | 컬럼 CHECK는 파싱되지만 강제되지 않음 | 📖 매뉴얼, 🧪 프로브 |
| `supportsTableCheck` | `false` | `true` | 테이블 CHECK는 파싱되지만 강제되지 않음 | 📖 매뉴얼, 🧪 프로브 |

**DDL — 스키마 · 테이블**

| 메서드 | CUBRID | 기본값 | 근거 | 출처 |
|---|---|---|---|---|
| `supportsTemporaryTables` | `false` | `true` | (global/local) 임시 테이블 없음 | 📖 매뉴얼 |
| `supportsIfExistsBeforeTableName` | `true` | `false` | DROP TABLE IF EXISTS 지원 | 📖 매뉴얼, 🧪 프로브 |
| `qualifyIndexName` | `false` | `true` | 인덱스 이름은 스키마로 한정되지 않음 | ⚙️ 기존 |
| `canCreateSchema` | `false` | `true` | CREATE SCHEMA 문 없음 | 📖 매뉴얼 |
| `supportsCommentOn` | `false` | `false` | 'COMMENT ON' 문 없음(인라인 COMMENT='...'만 존재) | 📖 매뉴얼 |

**현재 시각(current timestamp)**

| 메서드 | CUBRID | 기본값 | 근거 | 출처 |
|---|---|---|---|---|
| `supportsCurrentTimestampSelection` | `true` | `false` | 'select now()'로 현재 시각 조회 | ⚙️ 기존 |
| `isCurrentTimestampSelectStringCallable` | `false` | `(복합식)` | 'select now()'는 callable이 아니라 쿼리 | ⚙️ 기존 |

### 1b. 비-boolean capability 메서드

| 메서드 | CUBRID 값 | 근거 | 출처 |
|---|---|---|---|
| `getNullOrdering` | `NullOrdering.SMALLEST` | NULL을 최솟값으로 정렬: 오름차순→NULL 먼저, 내림차순→NULL 마지막 | 📖 매뉴얼, 🔬 e2e, 🐬 MySQL |
| `getLockingSupport` | `LockingSupportSimple(CLAUSE, NONE, FULL, NONE)` | FOR UPDATE 절로 비관적 잠금; lock-timeout 없음; outer join 잠금 가능 | 📖 매뉴얼 |
| `getIdentityColumnSupport` | `CUBRIDIdentityColumnSupport` | auto_increment 컬럼 + last_insert_id() | ⚙️ 기존, 🔬 e2e |
| `getSequenceSupport` | `CUBRIDSequenceSupport` | serial: 'create serial' / '<seq>.next_value' | ⚙️ 기존, 🔬 e2e |
| `getTimeZoneSupport` | `NATIVE` | datetimetz 타입이 시간대를 저장 | ⚙️ 기존 |
| `getMaxIdentifierLength` | `254` | CUBRID 식별자 길이 한도 | 📖 매뉴얼 |
| `getMaxVarcharLength` | `1073741823` | CUBRID VARCHAR 최대 | ⚙️ 기존 |
| `getMaxVarbinaryLength` | `1073741823` | CUBRID BIT VARYING 최대(길이 단위는 비트) | ⚙️ 기존 |
| `getDefaultStatementBatchSize` | `15` | JDBC 문장 배치 크기 | ⚙️ 기존 |
| `getDefaultTimestampPrecision` | `3` | datetime은 밀리초(3) 정밀도 | ⚙️ 기존 |
| `getFloatPrecision` | `21` | → 10진수 7자리 | ⚙️ 기존 |
| `getFractionalSecondPrecisionInNanos` | `1000000` | 밀리초 소수초 정밀도 | ⚙️ 기존 |

## 2. 상속된 기본값 — 검증 완료(의도적으로 override하지 않음)

기본값이 조용히 누락되지 않았음을 보이기 위해 명시적으로 확인한 항목들입니다.

| 메서드 | 기본값 | 비고 |
|---|---|---|
| `supportsCascadeDelete` | `true` | 📖 매뉴얼: ON DELETE CASCADE 지원(ON UPDATE CASCADE는 미지원 — 플래그 없음) — 기본값 `true` 정확 |
| `supportsCrossJoin` | `true` | 📖 매뉴얼: CROSS JOIN 지원 — 기본값 `true` 정확 |
| `supportsDistinctFromPredicate` | `false` | 🧪 프로브: IS DISTINCT FROM 미지원 — 기본값 `false` 정확 |
| `supportsExceptAll` | `supportsIntersect()` | 🧪 프로브: INTERSECT ALL / EXCEPT ALL 동작 — 기본값 `true` 정확 |
| `supportsForUpdate` | `getLockingMetadata().supportsForUpdate()` | LockingSupport(CLAUSE)에서 파생 → true |
| `supportsIfExistsBeforeIndexName` | `false` | 🧪 프로브: DROP INDEX IF EXISTS 거부 — 기본값 `false` 정확 |
| `supportsIntersect` | `true` | 🧪 프로브: INTERSECT 동작 — 기본값 `true` 정확 |
| `supportsJoinsInDelete` | `false` | 🔬 e2e: `true`면 CUBRIDSqlAstTranslator가 delete 조인을 렌더하지 않아 깨진 SQL(MySQL은 translator override 보유) → base `false`(서브쿼리 emulation)가 정확. 2026-06-07 override 제거 |
| `supportsLateral` | `false` | 🧪 프로브: LATERAL 미지원 — 기본값 `false` 정확 |
| `supportsNamedColumnCheck` | `supportsColumnCheck()` | supportsColumnCheck()=false에서 파생 → false (정확) |
| `supportsNoWait` | `getLockingMetadata().supportsNoWait()` | LockingSupport(NONE)에서 파생 → false |
| `supportsOrderByInSubquery` | `true` | 📖 매뉴얼: 서브쿼리 내 ORDER BY 지원 — 기본값 `true` 정확 |
| `supportsOuterJoinForUpdate` | `(복합식)` | LockingSupport(OuterJoinLockingType.FULL)에서 파생 |
| `supportsSkipLocked` | `getLockingMetadata().supportsSkipLocked()` | LockingSupport(NONE)에서 파생 → false |
| `supportsSubqueryOnMutatingTable` | `true` | 🧪 프로브: 허용됨 — CUBRID는 MySQL의 동일 테이블 제약이 없음 — 기본값 `true` 정확 |
| `supportsUnionAll` | `true` | UNION ALL 동작 — 기본값 `true` 정확 |
| `supportsValuesListForInsert` | `true` | 🧪 프로브: 다중행 INSERT 동작 — 기본값 `true` 정확 |
| `supportsWait` | `getLockingMetadata().supportsWait()` | LockingSupport(NONE)에서 파생 → false |
| `supportsWithClause` | `true` | top-level CTE 지원 — 기본값 `true` 정확 |

## 3. 후속 확인 후보 — 프로브 확인 완료 (결론: 모두 override 불필요)

2026-06-06 실제 CUBRID 10.2 프로브로 확인. 셋 다 기본값이 적합해 **override가 필요 없음**.

| 메서드 | 기본값(상속) | 프로브 결과 | 결론 |
|---|---|---|---|
| `getInExpressionCountLimit` | `0` (무제한) | 🧪 5000개 `IN(...)` 리스트 정상 동작 → 낮은 하드 한도 없음 | 기본값 `0` 유지 |
| `getDmlTargetColumnQualifierSupport` | `NONE` | 🧪 무한정 컬럼 UPDATE 정상(별칭·테이블명 한정 형태도 모두 수용) → 한정 불필요 | 기본값 `NONE` 유지 (CUBRID는 `TABLE_ALIAS`도 수용하나 불필요) |
| `getNameQualifierSupport` | `null` (JDBC 메타데이터에서 유추) | 🧪 메타데이터의 schema/catalog 지원 플래그 전부 false → `NONE`으로 유추 | 기본값 유지 (자동으로 NONE) |

`supportsValuesList`는 후속 후보였으나 이제 `true`로 **override됨**(프로브 확인).

---

## 부록 A — 모든 `public boolean` capability 플래그(전체 레퍼런스)

`CUBRID` 열은 override된 값을, 기본값을 쓰면 `— 상속 —`을 표시합니다.

| 메서드 | 기본값 | CUBRID |
|---|---|---|
| `addPartitionKeyToPrimaryKey` | `false` | — 상속 — |
| `canBatchTruncate` | `false` | — 상속 — |
| `canCreateCatalog` | `false` | — 상속 — |
| `canCreateSchema` | `true` | **`false`** |
| `canDisableConstraints` | `false` | — 상속 — |
| `doesReadCommittedCauseWritersToBlockReaders` | `false` | — 상속 — |
| `doesRepeatableReadCauseReadersToBlockWriters` | `false` | — 상속 — |
| `doesRoundTemporalOnOverflow` | `true` | — 상속 — |
| `dropConstraints` | `true` | — 상속 — |
| `forceLobAsLastValue` | `false` | — 상속 — |
| `getDefaultNonContextualLobCreation` | `false` | — 상속 — |
| `getDefaultUseGetGeneratedKeys` | `true` | — 상속 — |
| `hasAlterTable` | `true` | — 상속 — |
| `hasDataTypeBeforeGeneratedAs` | `true` | — 상속 — |
| `hasSelfReferentialForeignKeyBug` | `false` | — 상속 — |
| `isCurrentTimestampSelectStringCallable` | `(복합식)` | **`false`** |
| `isCurrentTimestampStable` | `false` | — 상속 — |
| `isEmptyStringTreatedAsNull` | `false` | — 상속 — |
| `isJdbcLogWarningsEnabledByDefault` | `true` | — 상속 — |
| `qualifyIndexName` | `true` | **`false`** |
| `requiresCastForConcatenatingNonStrings` | `false` | — 상속 — |
| `requiresColumnListInCreateView` | `false` | — 상속 — |
| `requiresFloatCastingOfIntegerDivision` | `false` | — 상속 — |
| `requiresNotNullBeforeDefault` | `false` | — 상속 — |
| `requiresParensForTupleCounts` | `supportsTupleCounts()` | — 상속 — |
| `requiresParensForTupleDistinctCounts` | `false` | — 상속 — |
| `stripsTrailingSpacesFromChar` | `false` | — 상속 — |
| `supportsAlterColumnType` | `false` | **`true`** |
| `supportsArrayConstructor` | `false` | — 상속 — |
| `supportsBindAsCallableArgument` | `true` | — 상속 — |
| `supportsBindingNullForSetObject` | `false` | — 상속 — |
| `supportsBindingNullSqlTypeForSetNull` | `false` | — 상속 — |
| `supportsBitType` | `true` | — 상속 — |
| `supportsCascadeDelete` | `true` | — 상속 — |
| `supportsCaseInsensitiveLike` | `false` | — 상속 — |
| `supportsCircularCascadeDeleteConstraints` | `true` | — 상속 — |
| `supportsColumnCheck` | `true` | **`false`** |
| `supportsCommentOn` | `false` | **`false`** |
| `supportsConflictClauseForInsertCTE` | `false` | — 상속 — |
| `supportsCrossJoin` | `true` | — 상속 — |
| `supportsCteHeaderColumnList` | `true` | — 상속 — |
| `supportsCurrentTimestampSelection` | `false` | **`true`** |
| `supportsDistinctFromPredicate` | `false` | — 상속 — |
| `supportsDuplicateSelectItemsInQueryGroup` | `true` | — 상속 — |
| `supportsExceptAll` | `supportsIntersect()` | — 상속 — |
| `supportsExistsInSelect` | `true` | **`false`** |
| `supportsExpectedLobUsagePattern` | `true` | — 상속 — |
| `supportsFilterClause` | `false` | — 상속 — |
| `supportsForUpdate` | `getLockingMetadata().supportsForUpdate()` | — 상속 — |
| `supportsFractionalTimestampArithmetic` | `true` | — 상속 — |
| `supportsFromClauseInUpdate` | `false` | **`false`** |
| `supportsIfExistsAfterAlterTable` | `false` | — 상속 — |
| `supportsIfExistsAfterConstraintName` | `false` | — 상속 — |
| `supportsIfExistsAfterTableName` | `false` | — 상속 — |
| `supportsIfExistsAfterTypeName` | `false` | — 상속 — |
| `supportsIfExistsBeforeConstraintName` | `false` | — 상속 — |
| `supportsIfExistsBeforeIndexName` | `false` | — 상속 — |
| `supportsIfExistsBeforeTableName` | `false` | **`true`** |
| `supportsIfExistsBeforeTypeName` | `false` | — 상속 — |
| `supportsInsertReturning` | `false` | — 상속 — |
| `supportsInsertReturningGeneratedKeys` | `false` | — 상속 — |
| `supportsInsertReturningRowId` | `supportsInsertReturning()` | — 상속 — |
| `supportsIntersect` | `true` | — 상속 — |
| `supportsIsTrue` | `false` | **`true`** |
| `supportsJoinInMutationStatementSubquery` | `true` | — 상속 — |
| `supportsJoinsInDelete` | `false` | — 상속 — |
| `supportsLateral` | `false` | — 상속 — |
| `supportsLobValueChangePropagation` | `true` | — 상속 — |
| `supportsLockTimeouts` | `getLockingMetadata().getLockTimeoutType( Timeouts.ONE_SECOND ) == LockTimeoutType.QUERY` | — 상속 — |
| `supportsMaterializedLobAccess` | `true` | — 상속 — |
| `supportsNamedColumnCheck` | `supportsColumnCheck()` | — 상속 — |
| `supportsNationalizedMethods` | `true` | — 상속 — |
| `supportsNestedSubqueryCorrelation` | `true` | — 상속 — |
| `supportsNestedWithClause` | `supportsWithClauseInSubquery()` | **`false`** |
| `supportsNoColumnsInsert` | `true` | — 상속 — |
| `supportsNoWait` | `getLockingMetadata().supportsNoWait()` | — 상속 — |
| `supportsNonQueryWithCTE` | `false` | **`true`** |
| `supportsNotNullAfterGeneratedAs` | `true` | — 상속 — |
| `supportsNullPrecedence` | `true` | — 상속 — |
| `supportsOffsetInSubquery` | `false` | **`true`** |
| `supportsOrderByInSubquery` | `true` | — 상속 — |
| `supportsOrdinalSelectItemReference` | `true` | — 상속 — |
| `supportsOuterJoinForUpdate` | `(복합식)` | — 상속 — |
| `supportsPartitionBy` | `false` | **`true`** |
| `supportsRecursiveCTE` | `false` | **`true`** |
| `supportsRecursiveCycleClause` | `false` | — 상속 — |
| `supportsRecursiveCycleUsingClause` | `false` | — 상속 — |
| `supportsRecursiveSearchClause` | `false` | — 상속 — |
| `supportsResultSetPositionQueryMethodsOnForwardOnlyCursor` | `true` | — 상속 — |
| `supportsRowConstructor` | `false` | — 상속 — |
| `supportsRowValueConstructorDistinctFromSyntax` | `supportsRowValueConstructorSyntax() && supportsDistinctFromPredicate()` | — 상속 — |
| `supportsRowValueConstructorGtLtSyntax` | `supportsRowValueConstructorSyntax()` | **`false`** |
| `supportsRowValueConstructorSyntax` | `true` | **`true`** |
| `supportsRowValueConstructorSyntaxInInList` | `true` | **`true`** |
| `supportsRowValueConstructorSyntaxInInSubQuery` | `supportsRowValueConstructorSyntaxInInList()` | **`false`** |
| `supportsRowValueConstructorSyntaxInQuantifiedPredicates` | `true` | **`false`** |
| `supportsSimpleQueryGrouping` | `true` | **`true`** |
| `supportsSkipLocked` | `getLockingMetadata().supportsSkipLocked()` | — 상속 — |
| `supportsStandardArrays` | `false` | — 상속 — |
| `supportsStandardCurrentTimestampFunction` | `true` | — 상속 — |
| `supportsSubqueryInSelect` | `true` | — 상속 — |
| `supportsSubqueryOnMutatingTable` | `true` | — 상속 — |
| `supportsSubselectAsInPredicateLHS` | `true` | — 상속 — |
| `supportsTableCheck` | `true` | **`false`** |
| `supportsTableOptions` | `false` | — 상속 — |
| `supportsTemporalLiteralOffset` | `false` | — 상속 — |
| `supportsTemporaryTablePrimaryKey` | `true` | — 상속 — |
| `supportsTemporaryTables` | `true` | **`false`** |
| `supportsTruncateWithCast` | `true` | — 상속 — |
| `supportsTupleCounts` | `false` | — 상속 — |
| `supportsTupleDistinctCounts` | `true` | **`false`** |
| `supportsUnboundedLobLocatorMaterialization` | `true` | — 상속 — |
| `supportsUnionAll` | `true` | — 상속 — |
| `supportsUnionInSubquery` | `supportsUnionAll()` | — 상속 — |
| `supportsUniqueConstraints` | `true` | — 상속 — |
| `supportsUpdateReturning` | `supportsInsertReturning()` | — 상속 — |
| `supportsUserDefinedTypes` | `false` | — 상속 — |
| `supportsValueLOBAccess` | `false` | — 상속 — |
| `supportsValuesList` | `false` | **`true`** |
| `supportsValuesListForInsert` | `true` | — 상속 — |
| `supportsWait` | `getLockingMetadata().supportsWait()` | — 상속 — |
| `supportsWindowFunctions` | `false` | **`true`** |
| `supportsWithClause` | `true` | — 상속 — |
| `supportsWithClauseInSubquery` | `supportsWithClause()` | **`true`** |
| `throttleDdl` | `false` | — 상속 — |
| `unquoteGetGeneratedKeys` | `false` | — 상속 — |
| `useArrayForMultiValuedParameters` | `supportsStandardArrays() && getPreferredSqlTypeCodeForArray() == SqlTypes.ARRAY` | — 상속 — |
| `useConnectionToCreateLob` | `!useInputStreamToInsertBlob()` | — 상속 — |
| `useCrossReferenceForeignKeys` | `false` | — 상속 — |
| `useInputStreamToInsertBlob` | `true` | — 상속 — |
| `useMaterializedLobWhenCapacityExceeded` | `supportsMaterializedLobAccess()` | — 상속 — |

## 부록 B — 비-boolean capability 메서드

**CUBRID가 override:**

| 메서드 | CUBRID 값 |
|---|---|
| `getNullOrdering` | `NullOrdering.SMALLEST` |
| `getLockingSupport` | `LockingSupportSimple(CLAUSE, NONE, FULL, NONE)` |
| `getIdentityColumnSupport` | `CUBRIDIdentityColumnSupport` |
| `getSequenceSupport` | `CUBRIDSequenceSupport` |
| `getTimeZoneSupport` | `NATIVE` |
| `getMaxIdentifierLength` | `254` |
| `getMaxVarcharLength` | `1073741823` |
| `getMaxVarbinaryLength` | `1073741823` |
| `getDefaultStatementBatchSize` | `15` |
| `getDefaultTimestampPrecision` | `3` |
| `getFloatPrecision` | `21` |
| `getFractionalSecondPrecisionInNanos` | `1000000` |

**기본값 상속:**

| 메서드 | 반환 타입 |
|---|---|
| `getAggregateSupport` | `AggregateSupport` |
| `getBatchLoadSizingStrategy` | `MultiKeyLoadSizingStrategy` |
| `getCallableStatementSupport` | `CallableStatementSupport` |
| `getDefaultDecimalPrecision` | `int` |
| `getDefaultIntervalSecondScale` | `int` |
| `getDefaultLobLength` | `long` |
| `getDmlTargetColumnQualifierSupport` | `DmlTargetColumnQualifierSupport` |
| `getFunctionalDependencyAnalysisSupport` | `FunctionalDependencyAnalysisSupport` |
| `getGroupBySelectItemReferenceStrategy` | `SelectItemReferenceStrategy` |
| `getInExpressionCountLimit` | `int` |
| `getLobMergeStrategy` | `LobMergeStrategy` |
| `getMaxAliasLength` | `int` |
| `getMaxNVarcharCapacity` | `int` |
| `getMaxNVarcharLength` | `int` |
| `getMaxVarbinaryCapacity` | `int` |
| `getMaxVarcharCapacity` | `int` |
| `getMultiKeyLoadSizingStrategy` | `MultiKeyLoadSizingStrategy` |
| `getNameQualifierSupport` | `NameQualifierSupport` |
| `getNationalizationSupport` | `NationalizationSupport` |
| `getNativeParameterMarkerStrategy` | `ParameterMarkerStrategy` |
| `getParameterCountLimit` | `int` |
| `getPersistentTemporaryTableStrategy` | `TemporaryTableStrategy` |
| `getPessimisticLockStyle` | `PessimisticLockStyle` |
| `getReadRowLockStrategy` | `RowLockStrategy` |
| `getSizeStrategy` | `SizeStrategy` |
| `getTemporalTableSupport` | `TemporalTableSupport` |
| `getWriteRowLockStrategy` | `RowLockStrategy` |

