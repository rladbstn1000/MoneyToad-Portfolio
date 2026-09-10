package com.potg.don.analysisJob.entity;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "analysis_job")
@Getter
@Setter
public class AnalysisJob {

	public enum Status {QUEUED, RUNNING, DONE, ERROR}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long userId;
	private String fileId;

	@Enumerated(EnumType.STRING)
	private Status status;

	private Integer retryCount;
	private String lastMessage;

	private Instant nextPollAt;   // 다음 조회 시각 (지수 백오프)
	private Instant leasedUntil;  // 워커 락(중복 처리 방지)

	private Instant createdAt;
	private Instant updatedAt;
}

