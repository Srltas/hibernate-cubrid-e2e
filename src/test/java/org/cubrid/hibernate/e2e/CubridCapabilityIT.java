package org.cubrid.hibernate.e2e;

import java.util.stream.Stream;

import org.cubrid.hibernate.e2e.support.AbstractCubridIT;
import org.hibernate.Session;
import org.hibernate.community.dialect.CUBRIDDialect;
import org.hibernate.dialect.DatabaseVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that each SQL-feature capability flag's declared value matches what real CUBRID accepts:
 * it reflects the declared value and fires a probe SQL straight at CUBRID. Run via {@code testCubridAll}
 * (10.2 → 11.4) it also catches a newer CUBRID diverging from the 10.2 baseline. Only flags
 * expressible as a single SQL probe are covered (not JDBC/LOB/internal capabilities).
 */
@DisplayName("CUBRID e2e — capability declarations match real CUBRID behavior")
class CubridCapabilityIT extends AbstractCubridIT {

	private static final CUBRIDDialect DIALECT = new CUBRIDDialect( DatabaseVersion.make( 10, 2 ) );

	static Stream<Arguments> sqlFeatureFlags() {
		return Stream.of(
				//    dialect method                              probe SQL exercising that feature
				flag( "supportsWindowFunctions",          "select row_number() over (order by id) from member" ),
				flag( "supportsRecursiveCTE",             "with recursive c(n) as (select 1 from db_root union all select n+1 from c where n<3) select n from c" ),
				flag( "supportsWithClause",               "with c as (select 1 as n from db_root) select n from c" ),
				flag( "supportsWithClauseInSubquery",     "select x.n from (with c as (select 1 as n from db_root) select n from c) x" ),
				flag( "supportsNestedWithClause",         "with a as (with b as (select 1 as n from db_root) select n from b) select n from a" ),
				flag( "supportsIntersect",                "select 1 from db_root intersect select 1 from db_root" ),
				flag( "supportsExceptAll",                "select 1 from db_root except all select 2 from db_root" ),
				flag( "supportsUnionAll",                 "select 1 from db_root union all select 2 from db_root" ),
				flag( "supportsUnionInSubquery",          "select t.x from (select 1 as x from db_root union all select 2 from db_root) t" ),
				flag( "supportsCrossJoin",                "select 1 from db_root a cross join db_root b" ),
				flag( "supportsOrderByInSubquery",        "select t.x from (select 1 as x from db_root order by 1) t" ),
				flag( "supportsDistinctFromPredicate",    "select 1 from db_root where 1 is distinct from 2" ),
				flag( "supportsLateral",                  "select 1 from db_root a , lateral (select 1 as x from db_root) b" ),
				flag( "supportsIsTrue",                   "select 1 from db_root where (1=1) is true" ),
				flag( "supportsValuesList",               "select t.x from (values (1),(2)) as t(x)" ),
				flag( "supportsExistsInSelect",           "select exists (select 1 from db_root) from db_root" ),
				flag( "supportsFilterClause",             "select count(*) filter (where 1=1) from db_root" ),
				flag( "supportsNonQueryWithCTE",          "with c as (select id from member where 1=0) delete from member where id in (select id from c)" ),
				flag( "supportsRowValueConstructorSyntax",                       "select 1 from db_root where (1,2) = (1,2)" ),
				flag( "supportsRowValueConstructorSyntaxInInList",               "select 1 from db_root where (1,2) in ((1,2),(3,4))" ),
				flag( "supportsRowValueConstructorGtLtSyntax",                   "select 1 from db_root where (1,2) < (1,3)" ),
				flag( "supportsRowValueConstructorSyntaxInInSubQuery",           "select 1 from db_root where (1,2) in (select 1,2 from db_root)" ),
				flag( "supportsRowValueConstructorSyntaxInQuantifiedPredicates", "select 1 from db_root where (1,2) = all (select 1,2 from db_root)" )
				//supportsFetchClause is not probed: it takes a FetchClauseType argument (no no-arg method), and CUBRID has no ANSI FETCH clause
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("sqlFeatureFlags")
	@DisplayName("declared flag value matches CUBRID acceptance")
	void declarationMatchesCubrid(String method, String probeSql) throws Exception {
		boolean declared = (boolean) CUBRIDDialect.class.getMethod( method ).invoke( DIALECT );
		boolean cubridAccepts = cubridAccepts( probeSql );
		assertThat( cubridAccepts )
				.as( "CUBRIDDialect.%s() declares %s, but CUBRID acceptance of the probe SQL is %s — they must agree",
						method, declared, cubridAccepts )
				.isEqualTo( declared );
	}

	@Test
	@DisplayName("supportsAlterColumnType: the ALTER ... MODIFY COLUMN clause the dialect emits is accepted by CUBRID")
	void alterColumnTypeClauseAcceptedByCubrid() {
		cubridAccepts( "drop table if exists alt_col_probe" );
		assertThat( cubridAccepts( "create table alt_col_probe (c smallint)" ) ).isTrue();

		String alterSql = DIALECT.getAlterTableString( "alt_col_probe" )
				+ " " + DIALECT.getAlterColumnTypeString( "c", "integer", "integer" );

		assertThat( cubridAccepts( alterSql ) )
				.as( "dialect emitted: %s", alterSql )
				.isTrue();

		cubridAccepts( "drop table if exists alt_col_probe" );
	}

	@Test
	@DisplayName("the no-arg constructor defaults to the CUBRID 10.2 minimum version")
	void defaultConstructorUsesMinimumVersion() {
		DatabaseVersion version = new CUBRIDDialect().getVersion();
		assertThat( version.getDatabaseMajorVersion() ).isEqualTo( 10 );
		assertThat( version.getDatabaseMinorVersion() ).isEqualTo( 2 );
	}

	@Test
	@DisplayName("getMaxIdentifierLength=254 is a valid truncation target — CUBRID accepts a 254-byte identifier")
	void maxIdentifierLengthAcceptedByCubrid() {
		assertThat( DIALECT.getMaxIdentifierLength() ).isEqualTo( 254 );

		cubridAccepts( "drop table if exists idlen_probe" );
		assertThat( cubridAccepts( "create table idlen_probe (" + "c".repeat( 254 ) + " integer)" ) )
				.as( "a 254-byte identifier (Hibernate's max generated length) is accepted by CUBRID" )
				.isTrue();
		cubridAccepts( "drop table if exists idlen_probe" );
	}

	private boolean cubridAccepts(String sql) {
		try {
			inTransaction( em -> em.unwrap( Session.class ).doWork( conn -> {
				try ( var st = conn.createStatement() ) {
					st.execute( sql );
				}
			} ) );
			return true;
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	private static Arguments flag(String method, String probeSql) {
		return Arguments.of( method, probeSql );
	}
}
