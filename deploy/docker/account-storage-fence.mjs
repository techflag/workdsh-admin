/** Browser presentation storage only; backend authorization never trusts this marker. */
export const accountOwnerKey = 'workdsh.enterprise.client-owner.v1';

/** Adopt only a server-verified account before the official client restores local views. */
export function adoptAccountStorage(local, session, owner) {
  const marker = 'workdsh.enterprise.client-owner.v1';
  const changed = local.getItem(marker) !== owner;
  const tabChanged = session.getItem(marker) !== owner;
  if (!changed && !tabChanged) return false;
  // Notify other documents before removing their saved views. They must leave
  // the old account rather than continue using a newly shared browser cookie.
  if (changed) local.setItem(marker, 'pending:' + owner);
  const prefixes = ['dsh.sidebar-right.v1.', 'dsh.sidebar-browser.v1.',
    'dsh.conversation.', 'dsh.user-questions.drafts.v1.', 'dsh.schedule.task-tab.v1.', 'dsh.workspace.view.v5.'];
  const exact = ['dsh.sessions.current', 'dsh.workspace.view.v5'];
  const keys = Array.from({length: local.length}, (_, index) => local.key(index));
  for (const key of changed ? keys : []) {
    if (key !== null && (exact.includes(key) || prefixes.some(prefix => key.startsWith(prefix)))) local.removeItem(key);
  }
  session.removeItem('localClipboard');
  if (changed) local.setItem(marker, owner);
  session.setItem(marker, owner);
  return true;
}

/** Runs synchronously before official script tags; no official modules are copied or replaced. */
export function installAccountStorageFence(owner, adopt) {
  let locked = false;
  const lock = () => {
    if (locked) return;
    locked = true;
    window.stop();
    document.documentElement.replaceChildren();
    window.location.replace('/login');
  };
  try {
    adopt(window.localStorage, window.sessionStorage, owner);
    const checkMarker = () => {
      try {
        if (window.localStorage.getItem('workdsh.enterprise.client-owner.v1') !== owner) lock();
      } catch { lock(); }
    };
    window.addEventListener('storage', event => {
      if (event.key === 'workdsh.enterprise.client-owner.v1' || event.key === null) checkMarker();
    });
    const checkAccount = async () => {
      checkMarker();
      if (locked) return;
      try {
        const response = await fetch('/api/auth/me', {credentials: 'same-origin', cache: 'no-store'});
        if (locked) return;
        if (!response.ok) { lock(); return; }
        const actor = await response.json();
        if (JSON.stringify([actor.organizationId, actor.id]) !== owner) lock();
      } catch { lock(); }
    };
    window.addEventListener('pageshow', event => {
      checkMarker();
      if (event.persisted) void checkAccount();
    });
    window.addEventListener('focus', () => void checkAccount());
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') void checkAccount();
    });
  } catch {
    // A blocked/quota-failed browser store must not expose another account's
    // saved drafts or tab metadata. Do not start the official application.
    lock();
  }
}

/** Prepend the owned account fence without changing the official application markup. */
export function fenceOfficialDocument(source, actor, nonce) {
  if (!actor || typeof actor.id !== 'string' || !actor.id || actor.id.length > 256
      || typeof actor.organizationId !== 'string' || !actor.organizationId || actor.organizationId.length > 256) {
    throw Error('Verified enterprise account required');
  }
  if (!/^[A-Za-z0-9_-]{24,128}$/.test(nonce)) throw Error('Invalid document nonce');
  const owner = JSON.stringify([actor.organizationId, actor.id]);
  const literal = JSON.stringify(owner).replaceAll('<', '\\u003c').replaceAll('\u2028', '\\u2028').replaceAll('\u2029', '\\u2029');
  const script = `<script nonce="${nonce}">(${installAccountStorageFence.toString()})(${literal},${adoptAccountStorage.toString()});</script>`;
  if (!/<head(?:\s[^>]*)?>/i.test(source)) throw Error('Official document head unavailable');
  return source.replace(/<head(?:\s[^>]*)?>/i, head => head + script);
}

/** Add only this document's nonce, preserving the upstream CSP restrictions. */
export function fenceContentSecurityPolicy(value, nonce) {
  if (value === undefined) return undefined;
  if (Array.isArray(value)) return value.map(policy => fenceContentSecurityPolicy(policy, nonce));
  const directives = new Map(String(value).split(';').map(part => part.trim()).filter(Boolean)
    .map(part => { const [key, ...tokens] = part.split(/\s+/); return [key.toLowerCase(), tokens]; }));
  const token = `'nonce-${nonce}'`;
  for (const key of ['script-src', ...directives.has('script-src-elem') ? ['script-src-elem'] : []]) {
    const sources = directives.get(key) ?? directives.get('default-src') ?? ["'self'"];
    // Adding a nonce disables CSP's legacy unsafe-inline allowance. Leave that
    // existing allowance intact so official boot globals are not blocked.
    if (sources.includes("'unsafe-inline'") && !sources.some(source => /^'(nonce-|sha(?:256|384|512)-)/.test(source))) continue;
    directives.set(key, [...sources.filter(source => source !== "'none'"), token]);
  }
  return [...directives].map(([key, tokens]) => [key, ...tokens].join(' ')).join('; ');
}
