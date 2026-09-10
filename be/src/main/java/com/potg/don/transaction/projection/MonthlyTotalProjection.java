package com.potg.don.transaction.projection;

public interface MonthlyTotalProjection {
	Integer getYear();

	Integer getMonth();

	Long getTotal();
}