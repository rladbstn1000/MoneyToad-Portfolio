package com.potg.don.global.config;

import java.time.YearMonth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.potg.don.demo.seed.DemoSeedService;
import com.potg.don.transaction.service.ChartPeriodResolver;

@Configuration(proxyBeanMethods = false)
public class ChartPeriodConfiguration {
	@Bean
	@Profile("!demo")
	ChartPeriodResolver originalChartPeriodResolver() {
		return userId -> YearMonth.now();
	}

	@Bean
	@Profile("demo")
	ChartPeriodResolver demoChartPeriodResolver(DemoSeedService seed) {
		return seed::requireAnchorMonth;
	}
}
