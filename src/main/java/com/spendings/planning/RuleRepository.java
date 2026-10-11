package com.spendings.planning;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RuleRepository extends JpaRepository<RecurringRule, UUID> {}
