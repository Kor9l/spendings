package com.spendings.goal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface GoalRepository extends JpaRepository<Goal, UUID> {

  List<Goal> findAllByOrderByPriorityDescCreatedAtAsc();
}

interface GoalContributionRepository extends JpaRepository<GoalContribution, UUID> {

  List<GoalContribution> findByGoalIdOrderByMadeOnDescCreatedAtDesc(UUID goalId);
}
