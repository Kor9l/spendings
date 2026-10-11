package com.spendings.category;

import com.spendings.shared.DomainException;
import com.spendings.shared.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Публичный API модуля категорий; изменение — только изнутри модуля. */
@Service
@Transactional(readOnly = true)
public class Categories {

  static final String DEFAULT_ICON = "🏷️";
  static final String DEFAULT_COLOR = "#6c757d";
  static final String UNCATEGORIZED = "Не разобрано";

  private final CategoryRepository categories;
  private final CategoryUsage usage;
  private final ApplicationEventPublisher events;

  Categories(CategoryRepository categories, CategoryUsage usage, ApplicationEventPublisher events) {
    this.categories = categories;
    this.usage = usage;
    this.events = events;
  }

  /** Все категории с учётом наследования иконки и цвета. */
  public Map<UUID, CategoryRef> all() {
    List<Category> all = categories.findAllByOrderBySortOrderAscNameAsc();
    Map<UUID, Category> byId =
        all.stream().collect(Collectors.toMap(Category::id, Function.identity()));
    var refs = new LinkedHashMap<UUID, CategoryRef>();
    for (Category c : all) {
      refs.put(c.id(), toRef(c, c.parentId() == null ? null : byId.get(c.parentId())));
    }
    return refs;
  }

  /** Дерево одного вида: категории верхнего уровня с подкатегориями. */
  public List<CategoryNode> tree(CategoryKind kind, boolean includeArchived) {
    Map<UUID, CategoryRef> all = all();
    var nodes = new ArrayList<CategoryNode>();
    for (CategoryRef top : all.values()) {
      if (top.kind() != kind || top.parentId() != null || (top.archived() && !includeArchived)) {
        continue;
      }
      List<CategoryRef> children =
          all.values().stream()
              .filter(c -> top.id().equals(c.parentId()))
              .filter(c -> includeArchived || !c.archived())
              .toList();
      nodes.add(new CategoryNode(top, children));
    }
    return nodes;
  }

  public Optional<CategoryRef> find(UUID id) {
    return Optional.ofNullable(all().get(id));
  }

  public CategoryRef get(UUID id) {
    return find(id).orElseThrow(() -> new NotFoundException("Категория"));
  }

  /** Категория «Не разобрано» этого вида: куда попадает импорт без подходящего правила. */
  @Transactional
  public UUID uncategorized(CategoryKind kind) {
    return categories.findAllByOrderBySortOrderAscNameAsc().stream()
        .filter(c -> c.kind() == kind && c.isTopLevel() && UNCATEGORIZED.equals(c.name()))
        .findFirst()
        .map(
            c -> {
              c.restore();
              return c.id();
            })
        .orElseGet(
            () ->
                categories
                    .saveAndFlush(new Category(kind, null, UNCATEGORIZED, "❓", "#adb5bd", 100))
                    .id());
  }

  // ---- изменение: только для контроллера модуля ----

  CategoryForm formFor(UUID id) {
    return CategoryForm.of(load(id));
  }

  @Transactional
  UUID create(CategoryForm form) {
    Category parent = validateParent(form.getParentId(), form.getKind(), null);
    String name = form.getName().strip();
    ensureNameIsFree(form.getKind(), form.getParentId(), name, null);
    int sortOrder =
        parent == null
            ? categories.maxTopLevelSortOrder(form.getKind()) + 1
            : categories.maxChildSortOrder(parent.id()) + 1;
    var category =
        new Category(
            form.getKind(),
            form.getParentId(),
            name,
            blankToNull(form.getIcon()),
            blankToNull(form.getColor()),
            sortOrder);
    return categories.saveAndFlush(category).id();
  }

  @Transactional
  void update(UUID id, CategoryForm form) {
    Category category = load(id);
    if (form.getParentId() != null && !categories.findByParentId(id).isEmpty()) {
      throw new DomainException("category.error.hasChildrenCannotNest");
    }
    validateParent(form.getParentId(), category.kind(), id);
    String name = form.getName().strip();
    ensureNameIsFree(category.kind(), form.getParentId(), name, id);
    category.update(
        form.getParentId(), name, blankToNull(form.getIcon()), blankToNull(form.getColor()));
  }

  /** Архив скрывает категорию из выбора при вводе, история операций не меняется. */
  @Transactional
  void archive(UUID id) {
    Category category = load(id);
    category.archive();
    categories.findByParentId(id).forEach(Category::archive);
  }

  @Transactional
  void restore(UUID id) {
    Category category = load(id);
    category.restore();
    if (category.parentId() != null) {
      load(category.parentId()).restore();
    }
  }

  /** Удалить можно только категорию без операций и без подкатегорий. */
  @Transactional
  void delete(UUID id) {
    Category category = load(id);
    if (!categories.findByParentId(id).isEmpty()) {
      throw new DomainException("category.error.hasChildren");
    }
    if (usage.isUsed(id)) {
      throw new DomainException("category.error.used");
    }
    categories.delete(category);
  }

  /**
   * Объединяет {@code sourceId} с {@code targetId}: операции переносятся, source удаляется.
   * Подкатегории source переезжают в target, а одноимённые — объединяются с подкатегориями target.
   */
  @Transactional
  void merge(UUID sourceId, UUID targetId) {
    Category source = load(sourceId);
    Category target = load(targetId);
    if (sourceId.equals(targetId) || sourceId.equals(target.parentId())) {
      throw new DomainException("category.error.mergeIntoItself");
    }
    if (source.kind() != target.kind()) {
      throw new DomainException("category.error.mergeKindMismatch");
    }
    List<Category> sourceChildren = categories.findByParentId(sourceId);
    if (!sourceChildren.isEmpty() && !target.isTopLevel()) {
      throw new DomainException("category.error.mergeChildrenIntoSubcategory");
    }

    Map<String, Category> targetChildrenByName =
        categories.findByParentId(targetId).stream()
            .collect(Collectors.toMap(c -> normalize(c.name()), Function.identity(), (a, b) -> a));
    int nextOrder = categories.maxChildSortOrder(targetId) + 1;
    for (Category child : sourceChildren) {
      Category twin = targetChildrenByName.get(normalize(child.name()));
      if (twin != null) {
        events.publishEvent(new CategoriesMerged(child.id(), twin.id()));
        categories.delete(child);
      } else {
        child.moveUnder(targetId, nextOrder++);
      }
    }
    categories.flush();

    events.publishEvent(new CategoriesMerged(sourceId, targetId));
    categories.delete(source);
  }

  /** Порядок внутри одного уровня после перетаскивания. */
  @Transactional
  void reorder(List<UUID> orderedIds) {
    Map<UUID, Category> byId =
        categories.findAllById(orderedIds).stream()
            .collect(Collectors.toMap(Category::id, Function.identity()));
    for (int i = 0; i < orderedIds.size(); i++) {
      Category category = byId.get(orderedIds.get(i));
      if (category != null) {
        category.moveTo(i + 1);
      }
    }
  }

  private @Nullable Category validateParent(
      @Nullable UUID parentId, CategoryKind kind, @Nullable UUID selfId) {
    if (parentId == null) {
      return null;
    }
    if (parentId.equals(selfId)) {
      throw new DomainException("category.error.ownParent");
    }
    Category parent = load(parentId);
    if (!parent.isTopLevel()) {
      throw new DomainException("category.error.tooDeep");
    }
    if (parent.kind() != kind) {
      throw new DomainException("category.error.parentKindMismatch");
    }
    return parent;
  }

  private void ensureNameIsFree(
      CategoryKind kind, @Nullable UUID parentId, String name, @Nullable UUID selfId) {
    boolean taken =
        categories.findAllByOrderBySortOrderAscNameAsc().stream()
            .anyMatch(
                c ->
                    c.kind() == kind
                        && Objects.equals(c.parentId(), parentId)
                        && normalize(c.name()).equals(normalize(name))
                        && !c.id().equals(selfId));
    if (taken) {
      throw new DomainException("category.error.nameTaken", name);
    }
  }

  private Category load(UUID id) {
    return categories.findById(id).orElseThrow(() -> new NotFoundException("Категория"));
  }

  private static CategoryRef toRef(Category c, @Nullable Category parent) {
    String icon = firstNonNull(c.icon(), parent == null ? null : parent.icon(), DEFAULT_ICON);
    String color = firstNonNull(c.color(), parent == null ? null : parent.color(), DEFAULT_COLOR);
    return new CategoryRef(
        c.id(),
        c.parentId(),
        parent == null ? null : parent.name(),
        c.kind(),
        c.name(),
        icon,
        color,
        c.archived());
  }

  private static String firstNonNull(@Nullable String a, @Nullable String b, String fallback) {
    return a != null ? a : b != null ? b : fallback;
  }

  private static String normalize(String name) {
    return name.strip().toLowerCase(java.util.Locale.ROOT);
  }

  private static @Nullable String blankToNull(@Nullable String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  /** Категория верхнего уровня и её подкатегории. */
  public record CategoryNode(CategoryRef category, List<CategoryRef> children) {}
}
