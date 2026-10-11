// CSRF-токен Spring Security для всех запросов htmx.
document.addEventListener('htmx:configRequest', (event) => {
  const token = document.querySelector('meta[name="_csrf"]')?.content;
  const header = document.querySelector('meta[name="_csrf_header"]')?.content;
  if (token && header) {
    event.detail.headers[header] = token;
  }
});

// Уведомления: сервер шлёт HX-Trigger: {"toast": {"level": "success", "message": "…"}}
document.addEventListener('toast', (event) => {
  const { level = 'success', message = '' } = event.detail ?? {};
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
});
