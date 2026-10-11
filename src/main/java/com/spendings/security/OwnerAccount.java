package com.spendings.security;

import java.time.Instant;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Учётная запись владельца. Она единственная: второй строке в {@code app_user} не даёт появиться
 * уникальный столбец {@code singleton}.
 */
@Service
class OwnerAccount implements UserDetailsService {

  private final AppUserRepository users;
  private final PasswordEncoder passwordEncoder;

  /** Пока владельца нет, каждый вызов {@link #isSetUp()} спрашивает базу; потом — нет. */
  private volatile boolean setUp;

  OwnerAccount(AppUserRepository users, PasswordEncoder passwordEncoder) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
  }

  boolean isSetUp() {
    if (!setUp) {
      setUp = users.count() > 0;
    }
    return setUp;
  }

  @Transactional
  void createOwner(String username, String rawPassword) {
    if (users.count() > 0) {
      throw new IllegalStateException("Учётная запись владельца уже создана");
    }
    users.save(new AppUser(username, passwordEncoder.encode(rawPassword), Instant.now()));
  }

  @Override
  @Transactional(readOnly = true)
  public UserDetails loadUserByUsername(String username) {
    return users
        .findByUsername(username)
        .map(u -> User.withUsername(u.username()).password(u.passwordHash()).roles("OWNER").build())
        .orElseThrow(() -> new UsernameNotFoundException(username));
  }
}
