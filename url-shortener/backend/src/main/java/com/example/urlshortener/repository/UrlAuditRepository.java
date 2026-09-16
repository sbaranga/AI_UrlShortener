package com.example.urlshortener.repository;

import com.example.urlshortener.model.UrlAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UrlAuditRepository extends JpaRepository<UrlAuditEvent, Long> {}
