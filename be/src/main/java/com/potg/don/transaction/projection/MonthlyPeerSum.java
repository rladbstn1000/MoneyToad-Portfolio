package com.potg.don.transaction.projection;

public interface MonthlyPeerSum {
	Integer getYear();

	Integer getMonth();

	Long getTotalAmount();
}
