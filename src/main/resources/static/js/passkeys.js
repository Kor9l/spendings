// Passkeys: регистрация ключа в настройках и вход по ключу. Протокол ведёт Spring Security:
// /webauthn/register/options → navigator.credentials.create → /webauthn/register,
// /webauthn/authenticate/options → navigator.credentials.get → /login/webauthn.
(() => {
  const supported = () => !!window.PublicKeyCredential && !!navigator.credentials;

  const toBytes = (base64url) => {
    const base64 = base64url.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(base64url.length / 4) * 4, '=');
    return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
  };
  const toBase64url = (buffer) =>
    btoa(String.fromCharCode(...new Uint8Array(buffer))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

  const csrf = () => {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
    return token && header ? { [header]: token } : {};
  };

  async function post(url, body) {
    const response = await fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', ...csrf() },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  }

  const message = (error) =>
    error?.name === 'NotAllowedError' ? 'Отменено или время ожидания истекло.' : 'Не получилось. Попробуйте ещё раз.';

  async function register(form) {
    const error = form.querySelector('[data-passkey-error]');
    error.textContent = '';
    try {
      const options = await post('/webauthn/register/options');
      const credential = await navigator.credentials.create({
        publicKey: {
          ...options,
          challenge: toBytes(options.challenge),
          user: { ...options.user, id: toBytes(options.user.id) },
          excludeCredentials: (options.excludeCredentials ?? []).map((c) => ({ ...c, id: toBytes(c.id) })),
        },
      });
      const result = await post('/webauthn/register', {
        publicKey: {
          label: form.elements.label.value.trim(),
          credential: {
            id: credential.id,
            rawId: toBase64url(credential.rawId),
            type: credential.type,
            authenticatorAttachment: credential.authenticatorAttachment,
            clientExtensionResults: credential.getClientExtensionResults(),
            response: {
              attestationObject: toBase64url(credential.response.attestationObject),
              clientDataJSON: toBase64url(credential.response.clientDataJSON),
              transports: credential.response.getTransports?.() ?? [],
            },
          },
        },
      });
      if (!result.success) throw new Error('registration failed');
      location.reload();
    } catch (e) {
      error.textContent = message(e);
    }
  }

  async function login(button) {
    const error = button.parentElement.querySelector('[data-passkey-error]');
    error.textContent = '';
    try {
      const options = await post('/webauthn/authenticate/options');
      const credential = await navigator.credentials.get({
        publicKey: {
          ...options,
          challenge: toBytes(options.challenge),
          allowCredentials: (options.allowCredentials ?? []).map((c) => ({ ...c, id: toBytes(c.id) })),
        },
      });
      const result = await post('/login/webauthn', {
        id: credential.id,
        rawId: toBase64url(credential.rawId),
        type: credential.type,
        authenticatorAttachment: credential.authenticatorAttachment,
        clientExtensionResults: credential.getClientExtensionResults(),
        response: {
          authenticatorData: toBase64url(credential.response.authenticatorData),
          clientDataJSON: toBase64url(credential.response.clientDataJSON),
          signature: toBase64url(credential.response.signature),
          userHandle: credential.response.userHandle ? toBase64url(credential.response.userHandle) : null,
        },
      });
      if (!result.authenticated) throw new Error('authentication failed');
      location.href = result.redirectUrl || '/';
    } catch (e) {
      error.textContent = message(e);
    }
  }

  function init(root) {
    const ok = supported();
    root.querySelectorAll('[data-passkey-unsupported]').forEach((el) => el.classList.toggle('d-none', ok));
    root.querySelectorAll('[data-passkey-login-block]').forEach((el) => el.classList.toggle('d-none', !ok));
    root.querySelectorAll('[data-passkey-register] button').forEach((el) => { el.disabled = !ok; });
  }

  document.addEventListener('submit', (event) => {
    const form = event.target.closest('[data-passkey-register]');
    if (!form) return;
    event.preventDefault();
    register(form);
  });
  document.addEventListener('click', (event) => {
    const button = event.target.closest('[data-passkey-login]');
    if (button) login(button);
  });
  document.addEventListener('DOMContentLoaded', () => init(document));
  document.addEventListener('htmx:afterSettle', (event) => init(event.target));
})();
