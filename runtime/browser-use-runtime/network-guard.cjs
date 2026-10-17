// Trusted owned-Chromium initialization hook. No page scanning, observations, or actions.
// Node process state is private from page scripts. Policy failures abort the request.
//
// Ownership: this hook is interception transport only. It classifies no URL, host, IP address,
// port or origin. Every HTTP(S) request, redirect hop, subresource, popup, frame, worker and
// WebSocket handshake is forwarded unchanged to the Java guard (BrowserNetworkGuard, which applies
// BrowserDestinationPolicy), and anything other than an explicit `allow: true` answer (error,
// malformed or oversized reply) aborts the request. Do not add destination rules here; change
// BrowserDestinationPolicy so the rules keep a single owner.
const http = require('node:http');
const { EventEmitter } = require('node:events');
const contexts = new WeakMap();
async function permitted(request) {
  const body = Buffer.from(JSON.stringify(request));
  return new Promise((resolve) => {
    const client = http.request(
      process.env.VELOCITY_BROWSER_GUARD_URL,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${process.env.VELOCITY_BROWSER_GUARD_TOKEN}`,
          'Content-Type': 'application/json',
          'Content-Length': body.length,
        },
      },
      (response) => {
        let answer = '';
        response.on('data', (chunk) => {
          answer += chunk;
          if (answer.length > 128) client.destroy();
        });
        response.on('end', () => {
          try {
            resolve(JSON.parse(answer));
          } catch {
            resolve({ allow: false });
          }
        });
        response.on('error', () => resolve({ allow: false }));
      },
    );
    client.on('error', () => resolve({ allow: false }));
    client.end(body);
  });
}

async function command(channel, method, params = {}) {
  return channel.send(method, params);
}

// Public CDP sessions expose a non-flattened child transport. Attach iframe targets before
// their scripts run; waiting for Playwright's frame navigation event leaves a network gap.
class ChildChannel extends EventEmitter {
  constructor(parent, sessionId) {
    super();
    this.parent = parent;
    this.sessionId = sessionId;
    this.pending = new Map();
    this.nextId = 0;
    this.closed = false;
  }
  send(method, params = {}) {
    if (this.closed) return Promise.reject(new Error('Velocity browser policy channel is closed'));
    return new Promise((resolve, reject) => {
      const id = ++this.nextId;
      this.pending.set(id, { resolve, reject });
      command(this.parent, 'Target.sendMessageToTarget', {
        sessionId: this.sessionId,
        message: JSON.stringify({ id, method, params }),
      }).catch(() => {
        const pending = this.pending.get(id);
        this.pending.delete(id);
        if (pending) pending.reject(new Error('Velocity browser policy channel is unavailable'));
      });
    });
  }
  receive(message) {
    if (this.closed) return;
    if (message.id !== undefined) {
      const pending = this.pending.get(message.id);
      this.pending.delete(message.id);
      if (pending)
        message.error
          ? pending.reject(new Error('Velocity browser policy command failed'))
          : pending.resolve(message.result);
    } else if (typeof message.method === 'string') this.emit(message.method, message.params);
  }
  dispose() {
    if (this.closed) return;
    this.closed = true;
    for (const pending of this.pending.values())
      pending.reject(new Error('Velocity browser policy channel is closed'));
    this.pending.clear();
    this.emit('close');
    this.removeAllListeners();
  }
}

async function installNetworkPolicy(channel, context, targetType = 'page') {
  const browserTarget = targetType === 'browser';
  const children = new Map();
  const pending = new Set();
  const attaching = new Set();
  let closed = false;
  const stop = () => {
    if (!context.isClosed()) context.close().catch(() => {});
  };
  const received = (event) => {
    try {
      children.get(event.sessionId)?.receive(JSON.parse(event.message));
    } catch {
      stop();
    }
  };
  const detached = (event) => {
    const child = children.get(event.sessionId);
    children.delete(event.sessionId);
    child?.dispose();
  };
  const attach = async (event) => {
    let child;
    try {
      let sessionId = event.sessionId;
      if (browserTarget) {
        // Chromium requires flattened browser-level autoattachment. Obtain a public
        // non-flattened transport while that attachment keeps the new target paused.
        attaching.add(event.targetInfo.targetId);
        sessionId = (
          await command(channel, 'Target.attachToTarget', {
            targetId: event.targetInfo.targetId,
            flatten: false,
          })
        ).sessionId;
      }
      child = new ChildChannel(channel, sessionId);
      children.set(sessionId, child);
      await installNetworkPolicy(child, context, event.targetInfo.type);
      await command(child, 'Runtime.runIfWaitingForDebugger');
      if (browserTarget) await command(channel, 'Target.detachFromTarget', { sessionId: event.sessionId });
    } catch {
      if (!child?.closed && !closed) stop();
    } finally {
      attaching.delete(event.targetInfo.targetId);
    }
  };
  const attached = (event) => {
    if (attaching.has(event.targetInfo.targetId)) return;
    const installing = attach(event);
    pending.add(installing);
    installing.finally(() => pending.delete(installing));
  };
  const paused = async (event) => {
    try {
      if (event.responseStatusCode !== undefined || event.responseErrorReason !== undefined) {
        if (
          event.responseErrorReason !== undefined ||
          ([301, 302, 303, 307, 308].includes(event.responseStatusCode) &&
            (event.responseHeaders || []).some(
              (header) => header.name.toLowerCase() === 'location' && header.value.trim(),
            ))
        ) {
          await command(channel, 'Fetch.continueResponse', { requestId: event.requestId });
          return;
        }
        // Chromium computes security policy before continueResponse header changes.
        // Fulfill the same response bytes so the narrow worker policies take effect.
        const original = await command(channel, 'Fetch.getResponseBody', { requestId: event.requestId });
        const body = original.base64Encoded ? original.body : Buffer.from(original.body).toString('base64');
        await command(channel, 'Fetch.fulfillRequest', {
          requestId: event.requestId,
          body,
          responseCode: event.responseStatusCode,
          ...(event.responseStatusText ? { responsePhrase: event.responseStatusText } : {}),
          responseHeaders: [
            ...(event.responseHeaders || []),
            {
              name: 'Content-Security-Policy',
              value:
                event.resourceType === 'Document' ? 'worker-src http: https:' : 'connect-src http: https:',
            },
          ],
        });
        return;
      }
      const request = event.request;
      const headers = request.headers;
      const document = event.resourceType === 'Document';
      // Chromium adds Sec-Fetch-Dest after interception. Bootstrap loads may be
      // reported as Script or Other; script-response CSP affects worker execution
      // but does not constrain an ordinary script executing in its parent document.
      const workerBootstrap = ['Script', 'Other'].includes(event.resourceType);
      const decision = await permitted({
        url: request.url,
        method: request.method,
        body: request.postData ?? null,
        bodyKnown: !request.hasPostData || request.postData !== undefined,
        headers,
        navigation: document,
        websocket: false,
        resourceType: event.resourceType.toLowerCase(),
      });
      if (decision.allow !== true) {
        await command(channel, 'Fetch.failRequest', {
          requestId: event.requestId,
          errorReason: 'BlockedByClient',
        });
        return;
      }
      await command(channel, 'Fetch.continueRequest', {
        requestId: event.requestId,
        interceptResponse: document || workerBootstrap,
      });
    } catch {
      if (!closed) {
        try {
          await command(channel, 'Fetch.failRequest', {
            requestId: event.requestId,
            errorReason: 'BlockedByClient',
          });
        } catch {
          if (!closed) stop();
        }
      }
    }
  };
  channel.on('Target.receivedMessageFromTarget', received);
  channel.on('Target.detachedFromTarget', detached);
  channel.on('Target.attachedToTarget', attached);
  channel.on('Fetch.requestPaused', paused);
  channel.on('close', () => {
    closed = true;
    for (const child of children.values()) child.dispose();
    children.clear();
    channel.off('Target.receivedMessageFromTarget', received);
    channel.off('Target.detachedFromTarget', detached);
    channel.off('Target.attachedToTarget', attached);
    channel.off('Fetch.requestPaused', paused);
  });
  // Browser attachment catches popups, shared workers and service workers. Recursing
  // through each target catches child frames and dedicated workers before scripts run.
  await command(channel, 'Target.setAutoAttach', {
    autoAttach: true,
    waitForDebuggerOnStart: true,
    flatten: browserTarget,
    filter: ['page', 'iframe', 'worker', 'shared_worker', 'service_worker']
      .map((type) => ({ type, exclude: false }))
      .concat({ exclude: true }),
  });
  // Dedicated-worker HTTP requests belong to their parent page's Fetch domain;
  // Chromium does not expose Fetch on dedicated-worker targets themselves.
  if (!browserTarget && targetType !== 'worker')
    await command(channel, 'Fetch.enable', { patterns: [{ urlPattern: '*', requestStage: 'Request' }] });
  while (pending.size) await Promise.all([...pending]);
}

module.exports.default = async ({ page }) => {
  if (!process.env.VELOCITY_BROWSER_GUARD_URL || !process.env.VELOCITY_BROWSER_GUARD_TOKEN)
    throw new Error('Velocity browser policy is unavailable');
  const context = page.context();
  // Stock console journaling can persist values echoed by login scripts. This integration
  // deliberately exposes no console tool and keeps these page events out of output files.
  page.removeAllListeners('console');
  page.removeAllListeners('pageerror');
  try {
    let setup = contexts.get(context);
    if (!setup) {
      setup = (async () => {
        // Java supplies already-canonical origins (BrowserDestinationPolicy.configuredOrigin). They only
        // scope Chromium's local-network permission; whether a request may reach them is decided in Java.
        for (const origin of JSON.parse(process.env.VELOCITY_BROWSER_PRIVATE_ORIGINS || '[]'))
          await context.grantPermissions(['local-network-access'], { origin });
        // Stock interception covers initial popup and service-worker script loads
        // before their CDP target is initialized. CDP additionally checks redirects,
        // which Playwright routing alone does not revisit at every request hop.
        await context.route('**/*', async (route) => {
          const request = route.request();
          const headers = await request.allHeaders();
          const decision = await permitted({
            url: request.url(),
            method: request.method(),
            body: request.postData(),
            bodyKnown: true,
            headers,
            navigation: request.isNavigationRequest(),
            websocket: false,
            resourceType: request.resourceType(),
          });
          if (decision.allow !== true || context.isClosed()) {
            await route.abort('blockedbyclient');
            return;
          }
          if (headers['service-worker'] === 'script') {
            // Worker WebSockets do not use stock frame routing. A bootstrap response
            // policy blocks them before worker code executes while retaining HTTP fetch.
            const response = await route.fetch({
              maxRedirects: 0,
              timeout: 0,
              headers: { ...headers, cookie: headers.cookie ?? '' },
            });
            const responseHeaders = response.headers();
            responseHeaders['content-security-policy'] = [
              responseHeaders['content-security-policy'],
              'connect-src http: https:',
            ]
              .filter(Boolean)
              .join(', ');
            await route.fulfill({ response, headers: responseHeaders });
            await response.dispose();
          } else await route.continue();
        });
        await context.routeWebSocket('**/*', async (socket) => {
          const url = socket.url().replace(/^ws:/, 'http:').replace(/^wss:/, 'https:');
          const cookie = (await context.cookies(url))
            .map((value) => value.name + '=' + value.value)
            .join('; ');
          const decision = await permitted({
            url,
            method: 'GET',
            body: null,
            bodyKnown: true,
            headers: { cookie, 'sec-websocket-protocol': socket.protocols().join(', ') },
            navigation: false,
            websocket: true,
            resourceType: 'websocket',
          });
          if (decision.allow !== true || context.isClosed()) {
            socket.close();
            return;
          }
          const server = socket.connectToServer();
          let forwarding = Promise.resolve();
          socket.onMessage((message) => {
            forwarding = forwarding
              .then(async () => {
                const decision = await permitted({
                  url,
                  method: 'WEBSOCKET',
                  body: Buffer.isBuffer(message) ? message.toString('utf8') : message,
                  bodyKnown: true,
                  headers: {},
                  navigation: false,
                  websocket: true,
                  resourceType: 'websocket',
                });
                if (decision.allow === true && !context.isClosed()) server.send(message);
                else {
                  await socket.close();
                  await server.close();
                }
              })
              .catch(async () => {
                await socket.close();
                await server.close();
              });
          });
          socket.onClose((code, reason) => {
            forwarding = forwarding
              .then(async () => {
                const decision = await permitted({
                  url,
                  method: 'WEBSOCKET',
                  body: reason,
                  bodyKnown: true,
                  headers: {},
                  navigation: false,
                  websocket: true,
                  resourceType: 'websocket',
                });
                if (decision.allow === true && !context.isClosed()) await server.close({ code, reason });
                else await server.close({ code: 4003, reason: 'Blocked by browser policy' });
              })
              .catch(() => server.close());
          });
        });
        const channel = await context.browser().newBrowserCDPSession();
        channel.on('close', () => {
          setImmediate(() => {
            if (!context.isClosed()) context.close().catch(() => {});
          });
        });
        await installNetworkPolicy(channel, context, 'browser');
      })();
      contexts.set(context, setup);
    }
    await setup;
  } catch {
    await context.close().catch(() => {});
    throw new Error('Velocity browser policy is unavailable');
  }
};
