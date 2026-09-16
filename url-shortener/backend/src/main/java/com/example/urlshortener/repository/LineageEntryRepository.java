package com.example.urlshortener.repository;

import com.example.urlshortener.model.LineageEntryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LineageEntryRepository extends JpaRepository<LineageEntryEntity, Long> {

    List<LineageEntryEntity> findAllByOrderBySequenceNumberAsc();

    Optional<LineageEntryEntity> findFirstByOrderBySequenceNumberDesc();

    Optional<LineageEntryEntity> findFirstByOrderBySequenceNumberAsc();
}
