package com.example.urlshortener.repository;

import com.example.urlshortener.model.UrlMapping;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UrlRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /** Id breaks ties so paging stays stable when two links share a createdAt timestamp. */
    Page<UrlMapping> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** Counts a click without loading the row, so concurrent redirects do not lose increments. */
    @Modifying
    @Transactional
    @Query("update UrlMapping u set u.clickCount = u.clickCount + 1 where u.shortCode = :shortCode")
    int incrementClickCount(@Param("shortCode") String shortCode);

    @Modifying
    @Transactional
    long deleteByShortCode(String shortCode);
}
