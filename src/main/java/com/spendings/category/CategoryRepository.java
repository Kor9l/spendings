package com.spendings.category;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CategoryRepository extends JpaRepository<Category, UUID> {

  List<Category> findAllByOrderBySortOrderAscNameAsc();

  List<Category> findByParentId(UUID parentId);

  @Query(
      "select coalesce(max(c.sortOrder), 0) from Category c"
          + " where c.kind = :kind and c.parentId is null")
  int maxTopLevelSortOrder(CategoryKind kind);

  @Query("select coalesce(max(c.sortOrder), 0) from Category c where c.parentId = :parentId")
  int maxChildSortOrder(UUID parentId);
}
