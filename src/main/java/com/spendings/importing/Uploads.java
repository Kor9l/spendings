package com.spendings.importing;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Загруженные, но ещё не импортированные файлы — в памяти, пока пользователь настраивает колонки.
 * Пользователь один, поэтому хватает нескольких последних; после перезапуска файл загружают заново.
 */
@Component
class Uploads {

  static final int KEEP = 5;

  private final Map<UUID, Upload> uploads = new ConcurrentHashMap<>();

  UUID put(String fileName, byte[] content) {
    UUID id = UUID.randomUUID();
    uploads.put(id, new Upload(id, fileName, content, Instant.now()));
    while (uploads.size() > KEEP) {
      uploads.values().stream()
          .min(Comparator.comparing(Upload::uploadedAt))
          .ifPresent(oldest -> uploads.remove(oldest.id()));
    }
    return id;
  }

  Optional<Upload> get(UUID id) {
    return Optional.ofNullable(uploads.get(id));
  }

  void remove(UUID id) {
    uploads.remove(id);
  }

  record Upload(UUID id, String fileName, byte[] content, Instant uploadedAt) {}
}
