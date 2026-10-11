package com.spendings.account;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AccountRepository extends JpaRepository<Account, UUID> {

  List<Account> findAllByOrderBySortOrderAscNameAsc();

  @Query("select coalesce(max(a.sortOrder), 0) from Account a")
  int maxSortOrder();
}
