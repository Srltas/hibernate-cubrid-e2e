package org.cubrid.hibernate.e2e;

import java.util.List;
import java.util.Locale;

import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;

import org.cubrid.hibernate.e2e.entity.Game;
import org.cubrid.hibernate.e2e.entity.Member;
import org.cubrid.hibernate.e2e.entity.Player;
import org.cubrid.hibernate.e2e.entity.SalesOrder;
import org.cubrid.hibernate.e2e.support.AbstractCubridIT;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CUBRIDDialect end-to-end on CUBRID (testcontainers)")
class CubridDialectIT extends AbstractCubridIT {

	@Nested
	@DisplayName("Pessimistic locking")
	class Locking {

		@Test
		@DisplayName("PESSIMISTIC_WRITE adds FOR UPDATE to the SELECT")
		void pessimisticWriteAddsForUpdate() {
			Long id = fromTransaction( em -> {
				Member m = new Member( "lock-target" );
				em.persist( m );
				return m.getId();
			} );

			sqlInspector.clear();

			Member locked = fromTransaction( em ->
					em.find( Member.class, id, LockModeType.PESSIMISTIC_WRITE ) );

			assertThat( locked ).isNotNull();
			assertThat( locked.getName() ).isEqualTo( "lock-target" );
			assertThat( sqlInspector.getSqlQueries() )
					.as( "PESSIMISTIC_WRITE must produce a FOR UPDATE clause" )
					.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( "for update" ) );
		}

		@Test
		@DisplayName("PESSIMISTIC_READ maps to FOR UPDATE (CUBRID has no FOR SHARE)")
		void pessimisticReadAlsoAddsForUpdate() {
			Long id = fromTransaction( em -> {
				Member m = new Member( "read-lock-target" );
				em.persist( m );
				return m.getId();
			} );

			sqlInspector.clear();

			Member locked = fromTransaction( em ->
					em.find( Member.class, id, LockModeType.PESSIMISTIC_READ ) );

			assertThat( locked ).isNotNull();
			assertThat( sqlInspector.getSqlQueries() )
					.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( "for update" ) );
		}

		@Test
		@DisplayName("LEFT JOIN combined with PESSIMISTIC_WRITE is accepted (OuterJoinLockingType.FULL)")
		void leftJoinWithPessimisticWriteSucceeds() {
			inTransaction( em -> {
				Game running = new Game( "WithPlayers", "RUNNING" );
				Game empty = new Game( "NoPlayers", "READY" );
				em.persist( running );
				em.persist( empty );
				em.persist( new Player( "p1", running ) );
			} );

			sqlInspector.clear();

			List<Game> games = fromTransaction( em -> em.createQuery(
					"select distinct g from Game g left join Player p on p.game.id = g.id",
					Game.class
			).setLockMode( LockModeType.PESSIMISTIC_WRITE ).getResultList() );

			assertThat( games ).hasSize( 2 );
			assertThat( sqlInspector.getSqlQueries() )
					.as( "FOR UPDATE must be emitted even with an outer join" )
					.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( "for update" ) );
		}
	}

	@Nested
	@DisplayName("Query feature support")
	class QueryFeatures {

		@Test
		@DisplayName("ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...) runs natively")
		void windowFunctionRowNumberOverPartitionBy() {
			inTransaction( em -> {
				em.persist( new SalesOrder( "Alice", 100L ) );
				em.persist( new SalesOrder( "Alice", 300L ) );
				em.persist( new SalesOrder( "Alice", 200L ) );
				em.persist( new SalesOrder( "Bob", 500L ) );
				em.persist( new SalesOrder( "Bob", 400L ) );
			} );

			sqlInspector.clear();

			List<Tuple> ranked = fromTransaction( em -> em.createQuery(
					"select o.customer as customer, o.amount as amount, "
							+ "row_number() over (partition by o.customer order by o.amount desc) as rn "
							+ "from SalesOrder o "
							+ "order by o.customer asc, o.amount desc",
					Tuple.class
			).getResultList() );

			assertThat( ranked ).hasSize( 5 );
			assertThat( ((Number) ranked.get( 0 ).get( "rn" )).intValue() ).isEqualTo( 1 );
			assertThat( ((Number) ranked.get( 0 ).get( "amount" )).longValue() ).isEqualTo( 300L );
			assertThat( ((Number) ranked.get( 1 ).get( "rn" )).intValue() ).isEqualTo( 2 );
			assertThat( ((Number) ranked.get( 2 ).get( "rn" )).intValue() ).isEqualTo( 3 );
			assertThat( ((Number) ranked.get( 3 ).get( "rn" )).intValue() ).isEqualTo( 1 );
			assertThat( ((Number) ranked.get( 3 ).get( "amount" )).longValue() ).isEqualTo( 500L );

			assertThat( sqlInspector.getSqlQueries() )
					.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( "over" ) );
		}

		@Test
		@DisplayName("WITH clause (CTE) inside a derived table is emitted, not emulated")
		void withClauseInSubqueryFiltersResults() {
			inTransaction( em -> {
				em.persist( new SalesOrder( "small", 50L ) );
				em.persist( new SalesOrder( "medium", 500L ) );
				em.persist( new SalesOrder( "large", 5000L ) );
				em.persist( new SalesOrder( "huge", 50000L ) );
			} );

			sqlInspector.clear();

			List<Tuple> bigOrders = fromTransaction( em -> em.createQuery(
					"with bigOrders as (select o.id as id, o.customer as customer, o.amount as amount "
							+ "                    from SalesOrder o where o.amount > 1000) "
							+ "select b.customer as customer, b.amount as amount from bigOrders b "
							+ "order by b.amount asc",
					Tuple.class
			).getResultList() );

			assertThat( bigOrders ).hasSize( 2 );
			assertThat( bigOrders.get( 0 ).get( "customer", String.class ) ).isEqualTo( "large" );
			assertThat( bigOrders.get( 1 ).get( "customer", String.class ) ).isEqualTo( "huge" );

			assertThat( sqlInspector.getSqlQueries() )
					.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( "with" ) );
		}

		@Test
		@DisplayName("Bulk DELETE with subquery removes only matching rows (supportsJoinsInDelete)")
		void bulkDeleteWithSubqueryRemovesOnlyMatchingRows() {
			inTransaction( em -> {
				Game running = new Game( "RunningGame", "RUNNING" );
				Game finished = new Game( "FinishedGame", "FINISHED" );
				em.persist( running );
				em.persist( finished );
				em.persist( new Player( "alpha", running ) );
				em.persist( new Player( "beta", running ) );
				em.persist( new Player( "gamma", finished ) );
			} );

			Long deleted = fromTransaction( em -> (long) em.unwrap( Session.class ).createMutationQuery(
					"delete from Player p where p.game.id in "
							+ "(select g.id from Game g where g.status = 'FINISHED')"
			).executeUpdate() );

			assertThat( deleted ).isEqualTo( 1L );

			Long remaining = fromTransaction( em -> em.createQuery(
					"select count(p) from Player p", Long.class ).getSingleResult() );
			assertThat( remaining ).isEqualTo( 2L );
		}

		@Test
		@DisplayName("setFirstResult + setMaxResults produces CUBRID's LIMIT n,m clause")
		void limitOffsetUsesCubridLimitClause() {
			inTransaction( em -> {
				for ( int i = 1; i <= 5; i++ ) {
					em.persist( new SalesOrder( "page-" + i, (long) ( i * 100 ) ) );
				}
			} );

			sqlInspector.clear();

			List<SalesOrder> page = fromTransaction( em -> em.createQuery(
					"select o from SalesOrder o order by o.amount asc", SalesOrder.class )
					.setFirstResult( 1 )
					.setMaxResults( 2 )
					.getResultList() );

			assertThat( page ).hasSize( 2 );
			assertThat( page.get( 0 ).getAmount() ).isEqualTo( 200L );
			assertThat( page.get( 1 ).getAmount() ).isEqualTo( 300L );

			assertThat( sqlInspector.getSqlQueries() )
					.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( "limit" ) );
		}

		@Test
		@DisplayName("Grouped UNION with outer ORDER BY is emitted (supportsSimpleQueryGrouping)")
		void unionOfGroupedQueriesWithOuterOrderBy() {
			inTransaction( em -> {
				em.persist( new SalesOrder( "low-A", 50L ) );
				em.persist( new SalesOrder( "low-B", 100L ) );
				em.persist( new SalesOrder( "high-A", 5000L ) );
				em.persist( new SalesOrder( "high-B", 9000L ) );
			} );

			sqlInspector.clear();

			List<Long> amounts = fromTransaction( em -> em.createQuery(
					"select o.amount from SalesOrder o where o.amount < 1000 "
							+ "union "
							+ "select o.amount from SalesOrder o where o.amount > 1000 "
							+ "order by 1 asc",
					Long.class
			).getResultList() );

			assertThat( amounts ).containsExactly( 50L, 100L, 5000L, 9000L );
			assertThat( sqlInspector.getSqlQueries() )
					.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( "union" ) );
		}

		@Test
		@DisplayName("ORDER BY ASC places NULLs first (CUBRID default NULL ordering)")
		void defaultNullOrderingPlacesNullsFirst() {
			inTransaction( em -> {
				em.persist( new SalesOrder( "with-amount-1", 100L ) );
				em.persist( new SalesOrder( "null-amount-1", null ) );
				em.persist( new SalesOrder( "with-amount-2", 200L ) );
				em.persist( new SalesOrder( "null-amount-2", null ) );
			} );

			List<SalesOrder> ordered = fromTransaction( em -> em.createQuery(
					"select o from SalesOrder o order by o.amount asc", SalesOrder.class )
					.getResultList() );

			assertThat( ordered ).hasSize( 4 );
			assertThat( ordered.get( 0 ).getAmount() ).isNull();
			assertThat( ordered.get( 1 ).getAmount() ).isNull();
			assertThat( ordered.get( 2 ).getAmount() ).isEqualTo( 100L );
			assertThat( ordered.get( 3 ).getAmount() ).isEqualTo( 200L );
		}
	}

	@Nested
	@DisplayName("ID generation")
	class IdGeneration {

		@Test
		@DisplayName("IDENTITY column round-trips persist and find")
		void identityColumnAssignsIdAndRoundTripsSuccessfully() {
			Member persisted = fromTransaction( em -> {
				Member m = new Member( "Alice" );
				em.persist( m );
				return m;
			} );

			assertThat( persisted.getId() ).isNotNull();

			Member loaded = fromTransaction( em -> em.find( Member.class, persisted.getId() ) );
			assertThat( loaded ).isNotNull();
			assertThat( loaded.getName() ).isEqualTo( "Alice" );
		}

		@Test
		@DisplayName("@SequenceGenerator yields strictly ascending IDs")
		void sequenceGeneratorAssignsAscendingIds() {
			SalesOrder o1 = fromTransaction( em -> {
				SalesOrder o = new SalesOrder( "first-buyer", 10L );
				em.persist( o );
				return o;
			} );
			SalesOrder o2 = fromTransaction( em -> {
				SalesOrder o = new SalesOrder( "second-buyer", 20L );
				em.persist( o );
				return o;
			} );
			SalesOrder o3 = fromTransaction( em -> {
				SalesOrder o = new SalesOrder( "third-buyer", 30L );
				em.persist( o );
				return o;
			} );

			assertThat( o1.getId() ).isNotNull();
			assertThat( o2.getId() ).isGreaterThan( o1.getId() );
			assertThat( o3.getId() ).isGreaterThan( o2.getId() );
		}

		@Test
		@DisplayName("INSERT uses CUBRID's native '.next_value' sequence syntax")
		void insertUsesCubridNextValueSyntax() {
			sqlInspector.clear();
			fromTransaction( em -> {
				SalesOrder o = new SalesOrder( "captured", 99L );
				em.persist( o );
				em.flush();
				return o;
			} );
			assertThat( sqlInspector.getSqlQueries() )
					.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( ".next_value" ) );
		}
	}
}
