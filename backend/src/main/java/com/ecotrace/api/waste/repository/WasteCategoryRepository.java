package com.ecotrace.api.waste.repository;

import com.ecotrace.api.waste.entity.WasteCategory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WasteCategoryRepository extends JpaRepository<WasteCategory, Short> {
    Optional<WasteCategory> findByCode(String code);
}
