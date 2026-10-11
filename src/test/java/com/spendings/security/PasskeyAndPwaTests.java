package com.spendings.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.spendings.IntegrationTest;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class PasskeyAndPwaTests {

  @Autowired MockMvcTester mvc;
  @Autowired PublicKeyCredentialUserEntityRepository users;
  @Autowired UserCredentialRepository credentials;
  @Autowired Passkeys passkeys;

  @Test
  void pwaFilesArePublicAndLinkedFromEveryPage() {
    assertThat(mvc.get().uri("/manifest.webmanifest"))
        .hasStatusOk()
        .bodyText()
        .contains("\"display\": \"standalone\"")
        .contains("/quick");
    assertThat(mvc.get().uri("/sw.js")).hasStatusOk().bodyText().contains("offline.html");
    assertThat(mvc.get().uri("/offline.html")).hasStatusOk();
    assertThat(mvc.get().uri("/icons/icon-512.png")).hasStatusOk();
    assertThat(mvc.get().uri("/login"))
        .hasStatusOk()
        .bodyText()
        .contains("rel=\"manifest\"")
        .contains("data-passkey-login");
  }

  @Test
  void registrationOptionsAreIssuedOnlyToTheSignedInOwner() {
    assertThat(
            mvc.post()
                .uri("/webauthn/register/options")
                .with(user("owner"))
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
        .hasStatusOk()
        .bodyJson()
        .hasPathSatisfying("$.challenge", v -> assertThat(v).isNotNull())
        .hasPathSatisfying("$.rp.id", v -> assertThat(v).asString().isEqualTo("localhost"))
        .hasPathSatisfying("$.user.name", v -> assertThat(v).asString().isEqualTo("owner"));

    // без входа параметров регистрации нет
    assertThat(mvc.post().uri("/webauthn/register/options").with(csrf())).hasStatus4xxClientError();
  }

  @Test
  void authenticationOptionsAreAvailableBeforeSignIn() {
    assertThat(
            mvc.post()
                .uri("/webauthn/authenticate/options")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
        .hasStatusOk()
        .bodyJson()
        .hasPathSatisfying("$.challenge", v -> assertThat(v).isNotNull())
        .hasPathSatisfying("$.rpId", v -> assertThat(v).asString().isEqualTo("localhost"));
  }

  @Test
  void ownerSeesAndDeletesOwnPasskeysOnly() {
    Bytes ownerId = Bytes.random();
    users.save(
        ImmutablePublicKeyCredentialUserEntity.builder()
            .name("owner")
            .id(ownerId)
            .displayName("owner")
            .build());
    Bytes credentialId = Bytes.random();
    credentials.save(
        ImmutableCredentialRecord.builder()
            .credentialId(credentialId)
            .userEntityUserId(ownerId)
            .publicKey(new ImmutablePublicKeyCose(new byte[] {1, 2, 3}))
            .signatureCount(0)
            .uvInitialized(true)
            .backupEligible(true)
            .backupState(true)
            .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
            .transports(Set.of(AuthenticatorTransport.INTERNAL))
            .label("iPhone")
            .created(Instant.now())
            .lastUsed(Instant.now())
            .build());

    assertThat(mvc.get().uri("/settings/passkeys").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("iPhone")
        .contains("синхронизируется");
    assertThat(passkeys.delete("someone-else", credentialId.toBase64UrlString())).isFalse();

    assertThat(
            mvc.post()
                .uri("/settings/passkeys/delete")
                .with(user("owner"))
                .with(csrf())
                .param("id", credentialId.toBase64UrlString()))
        .hasStatus3xxRedirection();
    assertThat(passkeys.of("owner")).isEmpty();
  }
}
