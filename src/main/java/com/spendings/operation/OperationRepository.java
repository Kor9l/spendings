package com.spendings.operation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface OperationRepository extends JpaRepository<Operation, UUID> {

  @Query("select o from Operation o where o.importId = :batchId")
  List<Operation> findByImportId(UUID batchId);
}
