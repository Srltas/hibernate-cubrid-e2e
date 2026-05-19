package org.cubrid.hibernate.e2e.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "game")
public class Game {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 20)
	private String status;

	/** Required by JPA. */
	protected Game() {
	}

	public Game(String title, String status) {
		this.title = title;
		this.status = status;
	}
}
