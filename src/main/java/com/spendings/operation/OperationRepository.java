package com.spendings.operation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OperationRepository extends JpaRepository<Operation, UUID> {}
