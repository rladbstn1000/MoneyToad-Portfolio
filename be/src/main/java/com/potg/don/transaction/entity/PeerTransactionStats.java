package com.potg.don.transaction.entity;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "peer_transaction_stats")
@Getter
public class PeerTransactionStats {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Integer ageGroup;

	private String gender;

	private LocalDate statsDate;

	private Integer amount;
}
