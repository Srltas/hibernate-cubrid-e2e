package org.cubrid.hibernate.e2e.support;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;

import org.hibernate.Session;
import org.hibernate.testing.jdbc.SQLStatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.cubrid.CubridContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots a CUBRID container (image tag from {@code cubrid.image.tag}, default
 * {@code 10.2}) and configures an {@link EntityManagerFactory} against it.
 * The shared {@link SQLStatementInspector} is reset before each test.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractCubridIT {

	private static final String CUBRID_IMAGE_TAG = System.getProperty( "cubrid.image.tag", "10.2" );

	@Container
	protected static final CubridContainer CUBRID = new CubridContainer( "cubrid/cubrid:" + CUBRID_IMAGE_TAG )
			.withDatabaseName( "hibernate_e2e" )
			.withUsername( "hibernate" )
			.withPassword( "hibernate" );

	protected EntityManagerFactory emf;

	protected final SQLStatementInspector sqlInspector = new SQLStatementInspector();

	@BeforeAll
	void setUpEntityManagerFactory() {
		Map<String, Object> properties = new HashMap<>();
		properties.put( "jakarta.persistence.jdbc.url", CUBRID.getJdbcUrl() );
		properties.put( "jakarta.persistence.jdbc.user", CUBRID.getUsername() );
		properties.put( "jakarta.persistence.jdbc.password", CUBRID.getPassword() );
		properties.put( "jakarta.persistence.jdbc.driver", "cubrid.jdbc.driver.CUBRIDDriver" );

		properties.put( "hibernate.dialect", "org.hibernate.community.dialect.CUBRIDDialect" );
		properties.put( "hibernate.hbm2ddl.auto", "create-drop" );
		properties.put( "hibernate.show_sql", "false" );
		properties.put( "hibernate.format_sql", "false" );
		properties.put( "hibernate.highlight_sql", "false" );

		properties.put( "hibernate.session_factory.statement_inspector", sqlInspector );

		emf = Persistence.createEntityManagerFactory( "cubrid-e2e", properties );
	}

	@BeforeEach
	void resetTestState() {
		cleanDatabase();
		sqlInspector.clear();
	}

	private void cleanDatabase() {
		inTransaction( em -> {
			Session session = em.unwrap( Session.class );
			// Children before parents to respect foreign keys.
			session.createMutationQuery( "delete from Player" ).executeUpdate();
			session.createMutationQuery( "delete from Game" ).executeUpdate();
			session.createMutationQuery( "delete from SalesOrder" ).executeUpdate();
			session.createMutationQuery( "delete from Member" ).executeUpdate();
		} );
	}

	@AfterAll
	void tearDownEntityManagerFactory() {
		if ( emf != null ) {
			emf.close();
		}
	}

	protected void inTransaction(Consumer<EntityManager> action) {
		EntityManager em = emf.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		try {
			tx.begin();
			action.accept( em );
			tx.commit();
		}
		catch (RuntimeException e) {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			throw e;
		}
		finally {
			em.close();
		}
	}

	protected <T> T fromTransaction(Function<EntityManager, T> action) {
		EntityManager em = emf.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		try {
			tx.begin();
			T result = action.apply( em );
			tx.commit();
			return result;
		}
		catch (RuntimeException e) {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			throw e;
		}
		finally {
			em.close();
		}
	}
}
