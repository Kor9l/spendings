// CSRF-токен Spring Security для всех запросов htmx.
document.addEventListener('htmx:configRequest', (event) => {
  const token = document.querySelector('meta[name="_csrf"]')?.content;
  const header = document.querySelector('meta[name="_csrf_header"]')?.content;
  if (token && header) {
    event.detail.headers[header] = token;
  }
});

// Уведомления: сервер шлёт HX-Trigger: {"toast": {"level": "success", "message": "…"}}
document.addEventListener('toast', (event) => showToast(event.detail ?? {}));

function showToast({ level = 'success', message = '' }) {
  const container = document.getElementById('toasts');
  if (!container || !message) return;

  const toast = document.createElement('div');
  toast.className = `toast align-items-center text-bg-${level} border-0`;
  toast.setAttribute('role', level === 'danger' ? 'alert' : 'status');

  const body = document.createElement('div');
  body.className = 'd-flex';
  const text = document.createElement('div');
  text.className = 'toast-body';
  text.textContent = message;
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'btn-close btn-close-white me-2 m-auto';
  close.setAttribute('data-bs-dismiss', 'toast');
  close.setAttribute('aria-label', 'Закрыть');

  body.append(text, close);
  toast.append(body);
  container.append(toast);
  toast.addEventListener('hidden.bs.toast', () => toast.remove());
  bootstrap.Toast.getOrCreateInstance(toast).show();
}

// Уведомление после перенаправления (flash-атрибут «toast»).
function showFlashToast() {
  const flash = document.getElementById('flash-toast');
  if (!flash) return;
  showToast({ level: flash.dataset.level, message: flash.dataset.message });
  flash.remove();
}

// Перетаскивание для сортировки: <ul data-sortable="/accounts/reorder"> с элементами data-id.
function initSortables(root) {
  root.querySelectorAll('[data-sortable]').forEach((list) => {
    if (list.sortable) return;
    list.sortable = Sortable.create(list, {
      // у вложенных списков свой «хвостик», чтобы родительский список не перехватывал перетаскивание
      handle: list.dataset.handle || '[data-drag-handle]',
      animation: 150,
      onEnd: () => {
        const ids = [...list.children].map((el) => el.dataset.id).filter(Boolean);
        // id=…&id=…: повторяющиеся параметры, поэтому fetch, а не htmx.ajax
        const body = new URLSearchParams(ids.map((id) => ['id', id]));
        fetch(list.dataset.sortable, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            [document.querySelector('meta[name="_csrf_header"]').content]:
              document.querySelector('meta[name="_csrf"]').content,
          },
          body,
        });
      },
    });
  });
}

document.addEventListener('DOMContentLoaded', () => {
  showFlashToast();
  initSortables(document);
});
document.addEventListener('htmx:afterSettle', (event) => {
  showFlashToast();
  initSortables(event.target);
});

// PWA: service worker кэширует статику и показывает офлайн-страницу без сети.
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => {}));
}
