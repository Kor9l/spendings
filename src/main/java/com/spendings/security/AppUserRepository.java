package com.spendings.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  Optional<AppUser> findByUsername(String username);
}
