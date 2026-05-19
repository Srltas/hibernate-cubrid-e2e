package org.cubrid.hibernate.e2e.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

// Named "SalesOrder" because "ORDER" is a reserved keyword.
@Entity
@Table(name = "sales_order")
public class SalesOrder {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sales_order_seq_gen")
	@SequenceGenerator(name = "sales_order_seq_gen", sequenceName = "sales_order_seq", allocationSize = 1)
	private Long id;

	@Column(nullable = false, length = 100)
	private String customer;

	@Column
	private Long amount;

	/** Required by JPA. */
	protected SalesOrder() {
	}

	public SalesOrder(String customer, Long amount) {
		this.customer = customer;
		this.amount = amount;
	}

	public Long getId() {
		return id;
	}

	public Long getAmount() {
		return amount;
	}
}
