// The refresh token is an HttpOnly cookie now; legacy versions stored it (and
// the account id) in localStorage. Purge every legacy key on load — the
// account is always derived from the authenticated user via /auth/me, never
// from localStorage.
localStorage.removeItem('refreshToken');
localStorage.removeItem('refresh_token');
localStorage.removeItem('accountId');
localStorage.removeItem('account_id');

// In-memory account cache (never persisted). Populated from the authenticated
// user's /auth/me response and from login/refresh responses.
let cachedAccountId = null;

// Credential sealing (encrypted transport): the browser seals the login and
// registration payloads with the auth service's RSA public key so the
// plaintext password never appears in the request body (including the
// DevTools Network tab). Web Crypto is only available in secure contexts
// (https, or http://localhost), so on non-secure hosts we gracefully fall back
// to the plaintext JSON contract; the server accepts both forms.
let cachedPublicKeyPem = null;

function bufToBase64(buf){
  let s = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < buf.length; i += CHUNK){
    s += String.fromCharCode.apply(null, buf.subarray(i, i + CHUNK));
  }
  return btoa(s);
}

function base64ToBuf(b64){
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

function randomHex(bytes){
  const a = new Uint8Array(bytes);
  crypto.getRandomValues(a);
  return Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('');
}

async function loadLoginPublicKey(){
  if (cachedPublicKeyPem) return cachedPublicKeyPem;
  const res = await fetch(AUTH_URL + '/auth/public-key');
  if (!res.ok) throw new Error('login public key unavailable');
  const data = await res.json();
  if (!data || typeof data.key !== 'string') throw new Error('invalid login public key');
  cachedPublicKeyPem = data.key;
  return cachedPublicKeyPem;
}

// Hybrid encryption: RSA-OAEP (SHA-256) wraps an ephemeral AES-256-GCM key;
// the ciphertext embeds the credentials plus a fresh nonce and a 60 s expiry so
// the server can reject replays. Returns null when sealing is unavailable.
async function sealCredentials(fields){
  if (typeof crypto === 'undefined' || !crypto.subtle || !crypto.getRandomValues) {
    return null;
  }
  try {
    const pem = await loadLoginPublicKey();
    const pemBody = pem
      .replace(/-----BEGIN PUBLIC KEY-----/g, '')
      .replace(/-----END PUBLIC KEY-----/g, '')
      .replace(/\s+/g, '');
    const spki = base64ToBuf(pemBody);
    const rsaKey = await crypto.subtle.importKey(
      'spki', spki.buffer,
      { name: 'RSA-OAEP', hash: 'SHA-256' },
      false,
      ['encrypt'],
    );
    const aesKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt']);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const sealed = Object.assign({}, fields, {
      nonce: randomHex(16),
      expiresAt: Date.now() + 60000,
    });
    const cipher = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      aesKey,
      new TextEncoder().encode(JSON.stringify(sealed)),
    );
    const rawAes = await crypto.subtle.exportKey('raw', aesKey);
    const encKey = await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, rsaKey, rawAes);
    return bufToBase64(new Uint8Array(encKey)) + '.' + bufToBase64(iv) + '.' + bufToBase64(new Uint8Array(cipher));
  } catch (err) {
    console.warn('Credential sealing unavailable; falling back to plaintext', err);
    return null;
  }
}

const PUBLIC_PATHS = new Set(['/', '/login', '/register', '/analytics']);

// Route guard: protected pages (dashboard, and anything else) redirect
// to /login when the user is not authenticated. The access token is verified
// against /auth/me; if it is expired the access token is renewed once,
// and if that also fails apiFetch bounces to /login. /analytics is PUBLIC and
// separate from the session (login/register too).
async function requireAuth(){
  if (PUBLIC_PATHS.has(window.location.pathname)) return;
  const jwt = localStorage.getItem('jwt');
  if (!jwt) {
    window.location.replace('/login');
    return;
  }
  try {
    const res = await apiFetch(AUTH_URL + '/auth/me');
    const data = await res.json();
    if (res.ok && data && data.accountId !== undefined && data.accountId !== null) {
      cachedAccountId = parseInt(data.accountId, 10);
      return;
    }
    window.location.replace('/login');
  } catch {
    window.location.replace('/login');
  }
}

document.addEventListener('DOMContentLoaded', requireAuth);

// Live password-requirement indicator on the registration page. The policy is
// length-only (minimum 12 characters), kept in step with the backend DTO.
function updatePasswordRequirements(value){
  const min = document.getElementById('reqMin');
  if (!min) return;
  const ok = typeof value === 'string' && value.length >= 12;
  min.textContent = (ok ? '✓ Minimum 12 characters' : '✗ Minimum 12 characters');
  min.className = 'pw-req' + (ok ? ' ok' : '');
}

(function initRegisterPage(){
  const form = document.getElementById('registerForm');
  if (!form) return;
  const pw = document.getElementById('password');
  if (pw) {
    updatePasswordRequirements(pw.value);
    pw.addEventListener('input', function(){ updatePasswordRequirements(pw.value); });
  }
})();

async function handleRegister(e){
  e.preventDefault();
  try{
    const body = {
      firstName: document.getElementById('firstName').value,
      middleName: document.getElementById('middleName').value || null,
      lastName: document.getElementById('lastName').value,
      username: document.getElementById('username').value,
      email: document.getElementById('email').value,
      password: document.getElementById('password').value,
      confirmPassword: document.getElementById('confirmPassword').value
    };
    if (body.password !== body.confirmPassword) {
      document.getElementById('message').innerText = 'Passwords do not match';
      return;
    }
    if (body.password.length < 12) {
      document.getElementById('message').innerText = 'Password must be at least 12 characters long';
      return;
    }
    const request = await sealCredentials(body);
    const payload = request ? { request } : body;
    const res = await fetch(AUTH_URL + '/auth/register', {method: 'POST', headers: {'Content-Type':'application/json'}, credentials: 'include', body: JSON.stringify(payload)});
    const data = await res.json();
    if(res.ok){
      window.location.href = '/login';
    } else {
      document.getElementById('message').innerText = data.message || JSON.stringify(data);
    }
  }catch(err){
    document.getElementById('message').innerText = 'Error: '+err;
  }
}

async function handleLogin(e){
  e.preventDefault();
  const identifier = document.getElementById('username').value;
  const password = document.getElementById('password').value;
  try{
    const request = await sealCredentials({identifier, password});
    const payload = request ? { request } : {identifier, password};
    const res = await fetch(AUTH_URL + '/auth/login', {method: 'POST', headers: {'Content-Type':'application/json'}, credentials: 'include', body: JSON.stringify(payload)});
    const data = await res.json();
    if(res.ok){
      storeTokens(data);
      window.location.href = '/dashboard';
    } else {
      document.getElementById('message').innerText = data.message || JSON.stringify(data);
    }
  }catch(err){
    document.getElementById('message').innerText = 'Error: '+err;
  }
}

// The refresh token is an HttpOnly+Secure cookie managed by the auth service;
// it is never stored in localStorage and never read by JavaScript. The auth
// service renews the access token every 15 minutes against the SAME refresh
// token, which stays valid for its whole 7-day life (the cookie is not
// re-issued). This helper asks for a new access token and returns it.
async function rotateAccessToken(){
  const res = await fetch(AUTH_URL + '/auth/refresh', {method: 'POST', headers: {'Content-Type':'application/json'}, credentials: 'include'});
  const data = await res.json();
  if(!res.ok) throw new Error(data.message || 'Session expired');
  storeTokens(data);
  return data.accessToken;
}

function storeTokens(data){
  localStorage.removeItem('refreshToken');
  localStorage.removeItem('refresh_token');
  localStorage.setItem('jwt', data.accessToken);
  if(data.user && data.user.accountId !== undefined && data.user.accountId !== null){
    cachedAccountId = parseInt(data.user.accountId, 10);
  }
}

function clearTokens(){
  localStorage.removeItem('jwt');
  cachedAccountId = null;
  localStorage.removeItem('refreshToken');
  localStorage.removeItem('refresh_token');
}

async function getAccountId(){
  if (cachedAccountId !== null && typeof cachedAccountId === 'number') {
    return cachedAccountId;
  }
  try{
    const res = await apiFetch(AUTH_URL + '/auth/me');
    const data = await res.json();
    if(res.ok && data.accountId !== undefined && data.accountId !== null){
      cachedAccountId = parseInt(data.accountId, 10);
      return cachedAccountId;
    }
  }catch(err){
    console.warn('getAccountId failed:', err);
  }
  clearTokens();
  window.location.replace('/login');
  throw new Error('Unable to determine account');
}

function logout(){
  clearTokens();
  try{
    fetch(AUTH_URL + '/auth/logout', {method: 'POST', credentials: 'include'}).catch(()=>{});
  }catch(err){
    console.warn('logout call failed:', err);
  }
  window.location.href = '/login';
}

// Utility: escape HTML
function escapeHtml(str){
  return String(str)
    .replace(/&/g,'&amp;')
    .replace(/</g,'&lt;')
    .replace(/>/g,'&gt;')
    .replace(/"/g,'&quot;')
    .replace(/'/g,'&#39;');
}

// Render object/array into readable HTML sections
function renderData(obj, title){
  const hasHeader = title !== null && title !== undefined;
  if (obj === null || obj === undefined) {
    return `<div class="pretty-section">${hasHeader?`<h4>${escapeHtml(title||'Result')}</h4>`:''}<div class="kv-row"><div class="kv-key">value</div><div class="kv-value muted">null</div></div></div>`;
  }

  if (Array.isArray(obj)){
    // render array items as individual sections (no numbering)
    if (obj.length > 0 && typeof obj[0] === 'object'){
      const items = obj.map(it => `<div class="item-wrap">${renderData(it, null)}</div>`).join('');
      if(hasHeader) return `<div class="pretty-section"><h4>${escapeHtml(title||'Items')}</h4><div class="pretty-list">${items}</div></div>`;
      return `<div class="pretty-list">${items}</div>`;
    } else {
      const itemsHtml = obj.map(it => `<div class="kv-row"><div class="kv-key"></div><div class="kv-value">${escapeHtml(String(it))}</div></div>`).join('');
      return hasHeader ? `<div class="pretty-section"><h4>${escapeHtml(title||'Items')}</h4>${itemsHtml}</div>` : itemsHtml;
    }
  }

  if (typeof obj === 'object'){
    const rows = Object.keys(obj).map(k=>{
      const v = obj[k];
      if (typeof v === 'object' && v !== null) return `<div class="kv-row"><div class="kv-key">${escapeHtml(k)}</div><div class="kv-value">${renderDataInline(v)}</div></div>`;
      return `<div class="kv-row"><div class="kv-key">${escapeHtml(k)}</div><div class="kv-value">${escapeHtml(String(v))}</div></div>`;
    }).join('');
    return `<div class="pretty-section">${hasHeader?`<h4>${escapeHtml(title||'Data')}</h4>`:''}${rows}</div>`;
  }

  return `<div class="pretty-section">${hasHeader?`<h4>${escapeHtml(title||'Value')}</h4>`:''}<div class="kv-row"><div class="kv-key">value</div><div class="kv-value">${escapeHtml(String(obj))}</div></div></div>`;
}

function renderDataInline(obj){
  // Inline small object representation (no outer heading)
  if (obj === null) return `<span class="muted">null</span>`;
  if (Array.isArray(obj)) return `<div>${obj.map(it=>escapeHtml(typeof it==='object'?JSON.stringify(it):String(it))).join(', ')}</div>`;
  if (typeof obj === 'object'){
    const parts = Object.keys(obj).map(k=>`<div style="margin-bottom:6px"><small class="kv-key">${escapeHtml(k)}:</small> <span class="kv-value">${escapeHtml(String(obj[k]))}</span></div>`).join('');
    return `<div>${parts}</div>`;
  }
  return `<span>${escapeHtml(String(obj))}</span>`;
}

// Wrapper to add Authorization header to requests when a JWT is present.
// On a 401 the access token is renewed once via /auth/refresh (the HttpOnly
// refresh cookie itself is never rotated — it keeps its 7-day life), and the
// request is retried. If refresh fails the user is redirected.
async function apiFetch(input, init = {}){
  const doFetch = async (token) => {
    const headers = new Headers(init.headers || {});
    if (token) headers.set('Authorization', 'Bearer ' + token);
    if (!headers.has('Accept')) headers.set('Accept', 'application/json');
    return fetch(input, Object.assign({}, init, { headers }));
  };

  let res = await doFetch(localStorage.getItem('jwt'));
  if (res.status === 401) {
    try {
      const newToken = await rotateAccessToken();
      res = await doFetch(newToken);
    } catch (err) {
      clearTokens();
      window.location.href = '/login';
      throw err;
    }
  }
  return res;
}

// Hardcoded map of symbol -> instrumentId (5 instruments)
const INSTRUMENT_MAP = {
  'AAPL': 1,
  'MSFT': 2,
  'GOOG': 3,
  'AMZN': 4,
  'TSLA': 5,
  'ABC': 999  // Invalid instrument for testing DLT
};

function generateIdempotencyKey(){
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2,10);
}

// Highlight the selected tab button
function highlightTab(btnId){
  document.querySelectorAll('.nav .btn.secondary').forEach(b=>b.classList.remove('active'));
  const el = document.getElementById(btnId);
  if(el) el.classList.add('active');
}

// Remove unwanted fields based on context and return a deep-cloned sanitized copy
function sanitizeForDisplay(data, context){
  const clone = JSON.parse(JSON.stringify(data));
  const toRemove = new Set();
  if(context === 'profile'){
    ['version','lastupdated','last_updated','updatedat','updated_at'].forEach(k=>toRemove.add(k));
  }
  if(context === 'orders' || context === 'orderSuccess'){
    ['orderid','id','order_id','idempotencykey','idempotency_key','idempotency','createdon','createdat','created_on','created_at','accountid','account_id','account'].forEach(k=>toRemove.add(k));
  }

  function strip(obj){
    if(!obj || typeof obj !== 'object') return obj;
    if(Array.isArray(obj)){
      obj.forEach(item=>strip(item));
      return obj;
    }
    Object.keys(obj).forEach(k=>{
      if(toRemove.has(k.toLowerCase())) delete obj[k];
      else if(typeof obj[k] === 'object') strip(obj[k]);
    });
    return obj;
  }
  return strip(clone);
}

async function showProfile(){
  const id = await getAccountId();
  const url = `${BACKEND_URL}/api/v1/accounts/${id}`;
  const res = await apiFetch(url);
  const data = await res.json();
  highlightTab('btnProfile');
  const safe = sanitizeForDisplay(data,'profile');
  document.getElementById('content').innerHTML = renderData(safe, 'Account Profile');
}

async function showHoldings(){
  const id = await getAccountId();
  const url = `${BACKEND_URL}/api/v1/accounts/${id}/positions`;
  const res = await apiFetch(url);
  const data = await res.json();
  highlightTab('btnHoldings');
  const safe = sanitizeForDisplay(data,'holdings');
  document.getElementById('content').innerHTML = renderData(safe, 'Holdings');
}

async function showOrders(){
  const id = await getAccountId();
  const url = `${BACKEND_URL}/api/v1/accounts/${id}/orders`;
  const res = await apiFetch(url);
  const data = await res.json();
  highlightTab('btnOrders');
  const safe = sanitizeForDisplay(data,'orders');
  document.getElementById('content').innerHTML = renderData(safe, 'Orders');
}

function showPlaceOrder(){
  // build options from INSTRUMENT_MAP keys
  const symbols = Object.keys(INSTRUMENT_MAP);
  const optionsHtml = symbols.map(s => `<option value="${s}">${s}</option>`).join('\n');
  highlightTab('btnPlaceOrder');
  document.getElementById('content').innerHTML = `<form id="orderForm" class="compact-form" onsubmit="submitOrder(event)">
      <label>Order Type</label>
      <div style="display:flex;gap:12px;margin-bottom:16px">
        <label style="display:flex;align-items:center;gap:6px">
          <input type="radio" name="orderType" value="MARKET" checked onchange="togglePriceField()">
          Market Order
        </label>
        <label style="display:flex;align-items:center;gap:6px">
          <input type="radio" name="orderType" value="LIMIT" onchange="togglePriceField()">
          Limit Order
        </label>
      </div>

      <label>Symbol</label>
      <select id="symbol" required>
        ${optionsHtml}
      </select>

      <div class="row">
        <div>
          <label>Quantity</label>
          <input id="quantity" type="number" step="1" required>
        </div>
        <div id="priceContainer" style="display:none">
          <label>Limit Price</label>
          <input id="price" type="number" step="0.01">
        </div>
      </div>

      <label>Side</label>
      <select id="side">
        <option value="BUY" selected>BUY</option>
        <option value="SELL">SELL</option>
      </select>
      <div style="display:flex;gap:8px;align-items:center;margin-top:8px">
        <button class="btn" type="submit">Place Order</button>
        <button type="button" class="btn secondary" onclick="document.getElementById('orderForm').reset();togglePriceField();">Reset</button>
      </div>
    </form>
    <div id="orderResult" class="order-result"></div>`;
}

function togglePriceField(){
  const orderType = document.querySelector('input[name="orderType"]:checked').value;
  const priceContainer = document.getElementById('priceContainer');
  const priceInput = document.getElementById('price');
  
  if(orderType === 'LIMIT'){
    priceContainer.style.display = 'block';
    priceInput.required = true;
  } else {
    priceContainer.style.display = 'none';
    priceInput.required = false;
    priceInput.value = '';
  }
}

async function submitOrder(e){
  e.preventDefault();
  // Read form fields (accountId comes from the logged-in user's JWT context)
  const accountId = await getAccountId();
  const symbol = document.getElementById('symbol').value;
  const quantity = parseInt(document.getElementById('quantity').value, 10);
  const price = parseFloat(document.getElementById('price').value);
  const side = document.getElementById('side').value;
  const orderType = document.querySelector('input[name="orderType"]:checked').value;

  if (!symbol || !quantity || !side) {
    document.getElementById('orderResult').innerText = 'Please fill all required fields.';
    return;
  }

  if (orderType === 'LIMIT' && (!price || isNaN(price))) {
    document.getElementById('orderResult').innerText = 'Limit price is required for limit orders.';
    return;
  }

  const body = {
    accountId: accountId,
    symbol: symbol,
    quantity: quantity,
    price: orderType === 'MARKET' ? 0 : price,
    side: side,
    orderType: orderType,
    idempotencyKey: generateIdempotencyKey()
  };

  const url = `${BACKEND_URL}/api/v1/orders`;
  const res = await apiFetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const data = await res.json();
  if(res.ok){
    const safe = sanitizeForDisplay(data,'orderSuccess');
    document.getElementById('orderResult').innerHTML = renderData(safe,'Order Success');
    
    // Auto-refresh order details after 2 seconds to get executed_price from executor
    setTimeout(async () => {
      try {
        const ordersUrl = `${BACKEND_URL}/api/v1/accounts/${accountId}/orders`;
        const ordersRes = await apiFetch(ordersUrl);
        if (ordersRes.ok) {
          const orders = await ordersRes.json();
          // Find the order we just placed by symbol and side
          const newOrder = orders.find(o => o.symbol === symbol && o.side === side && o.quantity === quantity);
          if (newOrder) {
            const safeSummary = sanitizeForDisplay(newOrder, 'orderDetails');
            document.getElementById('orderResult').innerHTML = renderData(safeSummary, 'Order Details (Refreshed)');
          }
        }
      } catch (err) {
        console.warn('Failed to refresh order details:', err);
      }
    }, 2000);
  } else {
    const safe = sanitizeForDisplay(data,'orders');
    document.getElementById('orderResult').innerHTML = renderData(safe,'Order Error');
  }
}
