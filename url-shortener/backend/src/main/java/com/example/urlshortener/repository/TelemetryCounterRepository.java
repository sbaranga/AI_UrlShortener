package com.example.urlshortener.repository;

import com.example.urlshortener.model.TelemetryCounter;
import com.example.urlshortener.orchestration.model.ModuleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelemetryCounterRepository extends JpaRepository<TelemetryCounter, ModuleId> {}
