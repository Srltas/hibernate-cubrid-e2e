package org.cubrid.hibernate.e2e;

import java.util.List;
import java.util.Locale;

import jakarta.persistence.LockModeType;

import org.cubrid.hibernate.e2e.entity.Game;
import org.cubrid.hibernate.e2e.entity.Member;
import org.cubrid.hibernate.e2e.entity.Player;
import org.cubrid.hibernate.e2e.support.AbstractCubridIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CUBRID e2e — pessimistic locking")
class CubridLockingIT extends AbstractCubridIT {

	@Test
	@DisplayName("PESSIMISTIC_WRITE adds FOR UPDATE to the SELECT")
	void pessimisticWriteAddsForUpdate() {
		Long id = persist( new Member( "lock-target" ) ).getId();

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
		Long id = persist( new Member( "read-lock-target" ) ).getId();

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
