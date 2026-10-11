package com.spendings.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "app_user")
class AppUser {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private @Nullable UUID id;

  @Column(nullable = false, unique = true)
  private String username;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AppUser() {}

  AppUser(String username, String passwordHash, Instant createdAt) {
    this.username = username;
    this.passwordHash = passwordHash;
    this.createdAt = createdAt;
  }

  String username() {
    return username;
  }

  String passwordHash() {
    return passwordHash;
  }
}
