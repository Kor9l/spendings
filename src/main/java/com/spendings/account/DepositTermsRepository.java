package com.spendings.account;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DepositTermsRepository extends JpaRepository<DepositTerms, UUID> {}
