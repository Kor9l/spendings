package com.spendings.security;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Service;

/**
 * Ключи входа (passkeys) владельца: список и удаление. Регистрацию и вход ведёт Spring Security.
 */
@Service
class Passkeys {

  private final PublicKeyCredentialUserEntityRepository users;
  private final UserCredentialRepository credentials;

  Passkeys(PublicKeyCredentialUserEntityRepository users, UserCredentialRepository credentials) {
    this.users = users;
    this.credentials = credentials;
  }

  List<Passkey> of(String username) {
    PublicKeyCredentialUserEntity user = users.findByUsername(username);
    if (user == null) {
      return List.of();
    }
    return credentials.findByUserId(user.getId()).stream()
        .map(
            c ->
                new Passkey(
                    c.getCredentialId().toBase64UrlString(),
                    c.getLabel(),
                    c.getCreated(),
                    c.getLastUsed(),
                    c.isBackupEligible()))
        .sorted(
            Comparator.comparing(Passkey::created, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  /** Удаляет ключ, только если он принадлежит этому пользователю. */
  boolean delete(String username, String credentialId) {
    PublicKeyCredentialUserEntity user = users.findByUsername(username);
    if (user == null) {
      return false;
    }
    Bytes id = Bytes.fromBase64(credentialId);
    CredentialRecord record = credentials.findByCredentialId(id);
    if (record == null || !record.getUserEntityUserId().equals(user.getId())) {
      return false;
    }
    credentials.delete(id);
    return true;
  }

  /**
   * @param synced ключ синхронизируется между устройствами (iCloud, Google)
   */
  record Passkey(
      String id,
      String label,
      @Nullable Instant created,
      @Nullable Instant lastUsed,
      boolean synced) {}
}
