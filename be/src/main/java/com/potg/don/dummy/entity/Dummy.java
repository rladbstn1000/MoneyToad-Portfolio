package com.potg.don.dummy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "dummy", indexes = {
	@Index(name = "idx_dummy_category", columnList = "category")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dummy {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(length = 50, nullable = false)
	private String category;

	@Column(name = "merchant_name", length = 100, nullable = false)
	private String merchantName;

	@Column(name = "min_amount", nullable = false)
	private Integer minAmount;

	@Column(name = "max_amount", nullable = false)
	private Integer maxAmount;
}