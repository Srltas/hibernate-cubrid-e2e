package org.cubrid.hibernate.e2e;

import java.util.List;
import java.util.Locale;

import jakarta.persistence.Tuple;

import org.cubrid.hibernate.e2e.entity.Game;
import org.cubrid.hibernate.e2e.entity.OrgUnit;
import org.cubrid.hibernate.e2e.entity.Player;
import org.cubrid.hibernate.e2e.entity.SalesOrder;
import org.cubrid.hibernate.e2e.support.AbstractCubridIT;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CUBRID e2e — query feature SQL generation")
class CubridQueryFeatureIT extends AbstractCubridIT {

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
	@DisplayName("Top-level WITH clause (CTE) is emitted and runs on CUBRID")
	void topLevelWithClauseFiltersResults() {
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
	@DisplayName("Bulk DELETE with a subquery filter removes only matching rows")
	void bulkDeleteWithSubqueryFilter() {
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
	@DisplayName("UNION of two queries with an outer ORDER BY runs on CUBRID")
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
	@DisplayName("Plain ORDER BY ASC: CUBRID natively orders NULLs first (native ordering, not the dialect flag)")
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

	@Test
	@DisplayName("Plain ORDER BY DESC: CUBRID natively orders NULLs last (native ordering, not the dialect flag)")
	void descNullOrderingPlacesNullsLast() {
		inTransaction( em -> {
			em.persist( new SalesOrder( "with-amount-1", 100L ) );
			em.persist( new SalesOrder( "null-amount-1", null ) );
			em.persist( new SalesOrder( "with-amount-2", 200L ) );
			em.persist( new SalesOrder( "null-amount-2", null ) );
		} );

		List<SalesOrder> ordered = fromTransaction( em -> em.createQuery(
				"select o from SalesOrder o order by o.amount desc", SalesOrder.class )
				.getResultList() );

		assertThat( ordered ).hasSize( 4 );
		assertThat( ordered.get( 0 ).getAmount() ).isEqualTo( 200L );
		assertThat( ordered.get( 1 ).getAmount() ).isEqualTo( 100L );
		assertThat( ordered.get( 2 ).getAmount() ).isNull();
		assertThat( ordered.get( 3 ).getAmount() ).isNull();
	}

	@Test
	@DisplayName("ORDER BY ASC ... NULLS LAST overrides CUBRID's default (explicit null precedence)")
	void explicitNullsLastOnAscending() {
		inTransaction( em -> {
			em.persist( new SalesOrder( "a", 100L ) );
			em.persist( new SalesOrder( "n", null ) );
			em.persist( new SalesOrder( "b", 200L ) );
		} );

		sqlInspector.clear();
		List<SalesOrder> ordered = fromTransaction( em -> em.createQuery(
				"select o from SalesOrder o order by o.amount asc nulls last", SalesOrder.class )
				.getResultList() );

		assertThat( ordered ).hasSize( 3 );
		assertThat( ordered.get( 0 ).getAmount() ).isEqualTo( 100L );
		assertThat( ordered.get( 1 ).getAmount() ).isEqualTo( 200L );
		assertThat( ordered.get( 2 ).getAmount() ).isNull();

		assertThat( sqlInspector.getSqlQueries() )
				.as( "getNullOrdering=SMALLEST makes Hibernate render the explicit keyword" )
				.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( "nulls last" ) );
	}

	@Test
	@DisplayName("ORDER BY DESC ... NULLS FIRST overrides CUBRID's default (explicit null precedence)")
	void explicitNullsFirstOnDescending() {
		inTransaction( em -> {
			em.persist( new SalesOrder( "a", 100L ) );
			em.persist( new SalesOrder( "n", null ) );
			em.persist( new SalesOrder( "b", 200L ) );
		} );

		sqlInspector.clear();
		List<SalesOrder> ordered = fromTransaction( em -> em.createQuery(
				"select o from SalesOrder o order by o.amount desc nulls first", SalesOrder.class )
				.getResultList() );

		assertThat( ordered ).hasSize( 3 );
		assertThat( ordered.get( 0 ).getAmount() ).isNull();
		assertThat( ordered.get( 1 ).getAmount() ).isEqualTo( 200L );
		assertThat( ordered.get( 2 ).getAmount() ).isEqualTo( 100L );

		assertThat( sqlInspector.getSqlQueries() )
				.as( "getNullOrdering=SMALLEST makes Hibernate render the explicit keyword" )
				.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( "nulls first" ) );
	}

	@Test
	@DisplayName("Recursive CTE (WITH ... UNION ALL self-reference) walks a tree (supportsRecursiveCTE)")
	void recursiveCteWalksTree() {
		inTransaction( em -> {
			OrgUnit root = new OrgUnit( "root", null );
			em.persist( root );
			OrgUnit mid = new OrgUnit( "mid", root );
			em.persist( mid );
			em.persist( new OrgUnit( "leaf", mid ) );
			em.persist( new OrgUnit( "sibling", root ) );
		} );

		sqlInspector.clear();

		// Column is named "lvl", not "depth": DEPTH is a reserved word in CUBRID.
		List<Tuple> rows = fromTransaction( em -> em.createQuery(
				"with tree as ("
						+ "  select root.id as id, root.name as name, 0 as lvl"
						+ "    from OrgUnit root where root.parent is null"
						+ "  union all"
						+ "  select child.id as id, child.name as name, parent.lvl + 1 as lvl"
						+ "    from tree parent join OrgUnit child on child.parent.id = parent.id"
						+ ") "
						+ "select t.name as name, t.lvl as lvl from tree t order by t.lvl, t.name",
				Tuple.class
		).getResultList() );

		assertThat( rows ).hasSize( 4 );
		assertThat( rows.get( 0 ).get( "name", String.class ) ).isEqualTo( "root" );
		assertThat( ((Number) rows.get( 0 ).get( "lvl" )).intValue() ).isEqualTo( 0 );
		assertThat( ((Number) rows.get( 1 ).get( "lvl" )).intValue() ).isEqualTo( 1 );
		assertThat( rows.get( 3 ).get( "name", String.class ) ).isEqualTo( "leaf" );
		assertThat( ((Number) rows.get( 3 ).get( "lvl" )).intValue() ).isEqualTo( 2 );

		assertThat( sqlInspector.getSqlQueries() )
				.anyMatch( sql -> sql.toLowerCase( Locale.ROOT ).contains( "recursive" ) );
	}
}
