async function handleLogin(e){
  e.preventDefault();
  const username=document.getElementById('username').value;
  const password=document.getElementById('password').value;
  // Auth server expects POST /api/auth (or GET /api/auth/verify)
  const url = AUTH_URL + '/api/auth';
  try{
    // Request a token for accountId=1 so the frontend can access account 1
    const res = await fetch(url, {method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({username, password, accountId: 1})});
    const data = await res.json();
    if(res.ok){
      // store token
      const token = data.token || data.accessToken || data?.data?.token;
      if(!token){
        document.getElementById('message').innerText = 'Login succeeded but token not found in response.';
        return;
      }
      localStorage.setItem('jwt',token);
      window.location.href = '/dashboard';
    } else {
      document.getElementById('message').innerText = data.message || JSON.stringify(data);
    }
  }catch(err){
    document.getElementById('message').innerText = 'Error: '+err;
  }
}

function logout(){
  localStorage.removeItem('jwt');
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

// Wrapper to add Authorization header to requests when a JWT is present
async function apiFetch(input, init = {}){
  const token = localStorage.getItem('jwt');
  const headers = new Headers(init.headers || {});
  if (token) {
    headers.set('Authorization', 'Bearer ' + token);
  }
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }
  const finalInit = Object.assign({}, init, { headers });
  return fetch(input, finalInit);
}

// Hardcoded map of symbol -> instrumentId (5 instruments)
const INSTRUMENT_MAP = {
  'AAPL': 1,
  'MSFT': 2,
  'GOOG': 3,
  'AMZN': 4,
  'TSLA': 5
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
  const id = 1;
  const url = `${BACKEND_URL}/api/v1/accounts/${id}`;
  const res = await apiFetch(url);
  const data = await res.json();
  highlightTab('btnProfile');
  const safe = sanitizeForDisplay(data,'profile');
  document.getElementById('content').innerHTML = renderData(safe, 'Account Profile');
}

async function showHoldings(){
  const id = 1;
  const url = `${BACKEND_URL}/api/v1/accounts/${id}/positions`;
  const res = await apiFetch(url);
  const data = await res.json();
  highlightTab('btnHoldings');
  const safe = sanitizeForDisplay(data,'holdings');
  document.getElementById('content').innerHTML = renderData(safe, 'Holdings');
}

async function showOrders(){
  const id = 1;
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
      <label>Symbol</label>
      <select id="symbol" required>
        ${optionsHtml}
      </select>

      <div class="row">
        <div>
          <label>Quantity</label>
          <input id="quantity" type="number" step="1" required>
        </div>
        <div>
          <label>Price</label>
          <input id="price" type="number" step="0.01" required>
        </div>
      </div>

      <label>Side</label>
      <select id="side">
        <option value="BUY" selected>BUY</option>
        <option value="SELL">SELL</option>
      </select>
      <div style="display:flex;gap:8px;align-items:center;margin-top:8px">
        <button class="btn" type="submit">Place Order</button>
        <button type="button" class="btn secondary" onclick="document.getElementById('orderForm').reset()">Reset</button>
      </div>
    </form>
    <div id="orderResult" class="order-result"></div>`;
}

async function submitOrder(e){
  e.preventDefault();
  // Read form fields
  const accountEl = document.getElementById('accountId');
  const accountId = accountEl ? parseInt(accountEl.value || '1', 10) : 1;
  const symbol = document.getElementById('symbol').value;
  const instrumentId = INSTRUMENT_MAP[symbol];
  const quantity = parseInt(document.getElementById('quantity').value, 10);
  const price = parseFloat(document.getElementById('price').value);
  const side = document.getElementById('side').value;

  if (!instrumentId || !quantity || !price || !side) {
    document.getElementById('orderResult').innerText = 'Please fill all required fields.';
    return;
  }

  const body = {
    accountId: accountId,
    instrumentId: instrumentId,
    symbol: symbol,
    quantity: quantity,
    price: price,
    side: side,
    idempotencyKey: generateIdempotencyKey()
  };

  const url = `${BACKEND_URL}/api/v1/orders`;
  const res = await apiFetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const data = await res.json();
  if(res.ok){
    const safe = sanitizeForDisplay(data,'orderSuccess');
    document.getElementById('orderResult').innerHTML = renderData(safe,'Order Success');
  } else {
    const safe = sanitizeForDisplay(data,'orders');
    document.getElementById('orderResult').innerHTML = renderData(safe,'Order Error');
  }
}
