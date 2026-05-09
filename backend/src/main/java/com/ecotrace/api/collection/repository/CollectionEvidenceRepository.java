package com.ecotrace.api.collection.repository;

import com.ecotrace.api.collection.entity.CollectionEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionEvidenceRepository extends JpaRepository<CollectionEvidence, UUID> {

    List<CollectionEvidence> findByCollectionId(UUID collectionId);
}
