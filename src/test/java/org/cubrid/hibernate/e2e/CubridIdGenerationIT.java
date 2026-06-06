package org.cubrid.hibernate.e2e;

import java.util.Locale;

import org.cubrid.hibernate.e2e.entity.Member;
import org.cubrid.hibernate.e2e.entity.SalesOrder;
import org.cubrid.hibernate.e2e.support.AbstractCubridIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CUBRID e2e — id generation")
class CubridIdGenerationIT extends AbstractCubridIT {

	@Test
	@DisplayName("IDENTITY column round-trips persist and find")
	void identityColumnAssignsIdAndRoundTripsSuccessfully() {
		Member persisted = persist( new Member( "Alice" ) );

		assertThat( persisted.getId() ).isNotNull();

		Member loaded = fromTransaction( em -> em.find( Member.class, persisted.getId() ) );
		assertThat( loaded ).isNotNull();
		assertThat( loaded.getName() ).isEqualTo( "Alice" );
	}

	@Test
	@DisplayName("@SequenceGenerator yields strictly ascending IDs")
	void sequenceGeneratorAssignsAscendingIds() {
		SalesOrder o1 = persist( new SalesOrder( "first-buyer", 10L ) );
		SalesOrder o2 = persist( new SalesOrder( "second-buyer", 20L ) );
		SalesOrder o3 = persist( new SalesOrder( "third-buyer", 30L ) );

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
