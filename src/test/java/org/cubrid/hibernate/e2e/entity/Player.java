package org.cubrid.hibernate.e2e.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "player")
public class Player {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String nickname;

	@ManyToOne
	@JoinColumn(name = "game_id")
	private Game game;

	/** Required by JPA. */
	protected Player() {
	}

	public Player(String nickname, Game game) {
		this.nickname = nickname;
		this.game = game;
	}
}
