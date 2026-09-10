package com.potg.don.transaction.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.potg.don.transaction.entity.PeerTransactionStats;
import com.potg.don.transaction.projection.MonthlyPeerSum;

public interface PeerTransactionStatsRepository extends JpaRepository<PeerTransactionStats, Long> {
	@Query("""
		    select 
		        YEAR(p.statsDate) as year,
		        MONTH(p.statsDate) as month,
		        SUM(p.amount) as totalAmount
		    from PeerTransactionStats p
		    where p.ageGroup = :ageGroup
		      and p.gender = :gender
		      and p.statsDate >= :startDate
		    group by YEAR(p.statsDate), MONTH(p.statsDate)
		    order by YEAR(p.statsDate), MONTH(p.statsDate)
		""")
	List<MonthlyPeerSum> findMonthlyPeerSum(
		@Param("ageGroup") Integer ageGroup,
		@Param("gender") String gender,
		@Param("startDate") LocalDate startDate
	);
}
