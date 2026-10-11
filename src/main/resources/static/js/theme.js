// Тема Bootstrap по настройке системы; подключается в <head> без defer, чтобы не мигало.
(() => {
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  const apply = () => document.documentElement.setAttribute('data-bs-theme', media.matches ? 'dark' : 'light');
  apply();
  media.addEventListener('change', apply);
})();
