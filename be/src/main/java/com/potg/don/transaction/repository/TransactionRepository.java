package com.potg.don.transaction.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.potg.don.transaction.entity.Transaction;
import com.potg.don.transaction.projection.CategoryTotalProjection;
import com.potg.don.transaction.projection.MonthlyCategoryTotalRow;
import com.potg.don.transaction.projection.MonthlyTotalProjection;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
	@Query("""
		select t
		from Transaction t
		  join t.card c
		  join c.user u
		where u.id = :userId
		  and t.transactionDateTime >= :start
		  and t.transactionDateTime < :end
		""")
	List<Transaction> findAllByUserAndBetween(
		@Param("userId") Long userId,
		@Param("start") LocalDateTime start,
		@Param("end") LocalDateTime end
	);

	/**
	 * 최근 12개월(포함 범위는 서비스에서 결정) 월별 총액 집계
	 */
	@Query("""
		select year(t.transactionDateTime) as year,
		       month(t.transactionDateTime) as month,
		       sum(t.amount) as total
		from Transaction t
		  join t.card c
		  join c.user u
		where u.id = :userId
		  and t.transactionDateTime >= :start
		  and t.transactionDateTime < :end
		group by year(t.transactionDateTime), month(t.transactionDateTime)
		order by year(t.transactionDateTime), month(t.transactionDateTime)
		""")
	List<MonthlyTotalProjection> aggregateMonthlyTotals(
		@Param("userId") Long userId,
		@Param("start") LocalDateTime start,
		@Param("end") LocalDateTime end
	);

	/**
	 * 특정 월 카테고리 합계
	 */
	@Query("""
		select t.category as category,
		       coalesce(sum(t.amount), 0) as total
		from Transaction t
		  join t.card c
		  join c.user u
		where u.id = :userId
		  and t.transactionDateTime >= :start
		  and t.transactionDateTime < :end
		group by t.category
		order by sum(t.amount) desc
		""")
	List<CategoryTotalProjection> aggregateCategoryTotals(
		@Param("userId") Long userId,
		@Param("start") LocalDateTime start,
		@Param("end") LocalDateTime end
	);

	@Query(value = """
		SELECT
		  YEAR(t.transaction_date_time)  AS y,
		  MONTH(t.transaction_date_time) AS m,
		  t.category                     AS category,
		  SUM(t.amount)                  AS total
		FROM transactions t
		WHERE t.card_id = :cardId
		  AND t.transaction_date_time >= :start   -- inclusive
		  AND t.transaction_date_time <  :end     -- exclusive
		GROUP BY y, m, t.category
		ORDER BY y ASC, m ASC
		""", nativeQuery = true)
	List<MonthlyCategoryTotalRow> aggregateCategoryTotalsByMonthForCard(
		@Param("cardId") Long cardId,
		@Param("start") LocalDateTime start,
		@Param("end") LocalDateTime end
	);

	// 카드 한 장의 월별 카테고리 합계 (보험/세금 제외, null/빈 카테고리는 '기타'로 취급)
	@Query(value = """
		SELECT
		    YEAR(t.transaction_date_time) AS y,
		    MONTH(t.transaction_date_time) AS m,
		    COALESCE(NULLIF(t.category, ''), '기타') AS category,
		    SUM(t.amount) AS total
		FROM transactions t
		WHERE t.card_id = :cardId
		  AND t.transaction_date_time >= :start
		  AND t.transaction_date_time < :end
		  AND (
		        COALESCE(NULLIF(t.category, ''), '기타') IN (:allowed) 
		      )
		GROUP BY y, m, category
		ORDER BY y, m
		""", nativeQuery = true)
	List<MonthlyCategoryTotalRow> aggregateCategoryTotalsByMonthForCardFiltered(
		@Param("cardId") Long cardId,
		@Param("start") LocalDateTime start,
		@Param("end") LocalDateTime end,
		@Param("allowed") List<String> allowed
	);

	/**
	 * 소유권 검증 포함 단건 조회 (업데이트용)
	 */
	Optional<Transaction> findByIdAndCard_User_Id(Long id, Long userId);

	List<Transaction> findAllByCard_IdOrderByTransactionDateTimeAsc(Long cardId);

	void deleteByCardId(Long cardId);
}
