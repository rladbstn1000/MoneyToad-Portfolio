package com.potg.don.dummy.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.potg.don.dummy.entity.Dummy;

public interface DummyRepository extends JpaRepository<Dummy, Long> {

	// MySQL 기준: 카테고리에서 랜덤 1건 뽑기
	@Query(value = """
		SELECT * FROM dummy
		WHERE category = :category
		ORDER BY RAND()
		LIMIT 1
		""", nativeQuery = true)
	Optional<Dummy> pickRandomOne(@Param("category") String category);
}
