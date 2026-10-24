// ProGenie front end on Cloudflare Workers (free, no card). See docs/DEPLOYMENT_NOCARD.md.
//
// Static files (the Angular build) are served by Cloudflare directly; wrangler.jsonc sends only /api/* and
// /mailpit/* to this code, which forwards them to the back end (BACKEND_URL: your laptop's tunnel or a server).
//
// Why a proxy instead of calling the back end from the browser: the site and the API then share one origin, so the
// refresh-token cookie (HttpOnly, SameSite=Strict, Path=/api/v1/auth) keeps working, and there is no CORS.
//
// Settings: BACKEND_URL (a var, passed by scripts/deploy-web.sh) and the secret EDGE_SECRET
// (wrangler secret put EDGE_SECRET), shared with the back end, which refuses every request that doesn't carry it.

const PROXIED = ['/api/', '/mailpit/'];

// Not forwarded: connection-level headers, Cloudflare's own headers, and anything a visitor could use to
// impersonate the Worker (the back end trusts the X-PG-* headers only because they come with EDGE_SECRET).
const DROP = new Set([
  'host', 'connection', 'keep-alive', 'proxy-connection', 'te', 'trailer', 'transfer-encoding',
  'forwarded', 'x-forwarded-for', 'x-forwarded-host', 'x-forwarded-proto', 'x-real-ip', 'true-client-ip',
  'cf-connecting-ip', 'cf-connecting-ipv6', 'cf-ipcountry', 'cf-ray', 'cf-visitor', 'cf-worker', 'cdn-loop',
  'authorization',
]);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/mailpit') {
      return Response.redirect(`${url.origin}/mailpit/`, 301);
    }
    if (PROXIED.some((prefix) => url.pathname.startsWith(prefix))) {
      return forward(request, env, url);
    }
    return env.ASSETS.fetch(request);
  },
};

async function forward(request, env, url) {
  if (!env.BACKEND_URL || !env.EDGE_SECRET) {
    return problem(500, 'EDGE_NOT_CONFIGURED', 'The site is missing BACKEND_URL or EDGE_SECRET (docs/DEPLOYMENT_NOCARD.md).');
  }
  const target = new URL(url.pathname + url.search, env.BACKEND_URL);

  const headers = new Headers();
  for (const [name, value] of request.headers) {
    if (!DROP.has(name) && !name.startsWith('x-pg-')) {
      headers.set(name, value);
    }
  }
  if (request.headers.has('upgrade')) {          // Mailpit's live updates use a WebSocket
    headers.set('connection', 'Upgrade');
  }
  // Under our own names, so nothing between here and the back end can overwrite or consume them
  headers.set('x-pg-edge', env.EDGE_SECRET);
  headers.set('x-pg-client-ip', request.headers.get('cf-connecting-ip') ?? '');
  headers.set('x-pg-host', url.host);
  if (request.headers.has('authorization')) {
    headers.set('x-pg-authorization', request.headers.get('authorization'));
  }

  const hasBody = request.method !== 'GET' && request.method !== 'HEAD';
  let response;
  try {
    response = await fetch(target, {
      method: request.method,
      headers,
      body: hasBody ? request.body : undefined,
      redirect: 'manual',
    });
  } catch {
    return starting();
  }
  if (response.webSocket) {
    return response;
  }

  // 502/503/504 come from Caddy while the API restarts; 530 from Cloudflare when the tunnel or server is down
  if ([502, 503, 504, 530].includes(response.status)) {
    return starting();
  }

  const out = new Response(response.body, response);
  out.headers.delete('server');
  out.headers.delete('x-powered-by');
  return out;
}

function starting() {
  const res = problem(503, 'BACKEND_STARTING',
    'The server is offline or restarting. Please try again in a few minutes.');
  res.headers.set('retry-after', '60');
  return res;
}

function problem(status, code, detail) {
  return new Response(JSON.stringify({ status, title: code, code, detail }), {
    status,
    headers: { 'content-type': 'application/problem+json', 'cache-control': 'no-store' },
  });
}
