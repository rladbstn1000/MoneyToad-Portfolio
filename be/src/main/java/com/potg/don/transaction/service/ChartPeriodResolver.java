package com.potg.don.transaction.service;

import java.time.YearMonth;

@FunctionalInterface
public interface ChartPeriodResolver {
	YearMonth endMonth(Long userId);
}
