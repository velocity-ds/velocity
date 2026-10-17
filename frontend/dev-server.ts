import { isIP } from 'node:net';
import type { IncomingMessage } from 'node:http';
import type { Plugin, ProxyOptions } from 'vite';

export function isLoopbackPeer(address: string | undefined): boolean {
  if (!address) return false;
  if (address.startsWith('::ffff:')) address = address.slice(7);
  return address === '::1' || (isIP(address) === 4 && address.startsWith('127.'));
}

export function hasUnambiguousBoundaryHeaders(request: Pick<IncomingMessage, 'rawHeaders'>): boolean {
  const counts = new Map<string, number>();
  for (let index = 0; index < request.rawHeaders.length; index += 2) {
    const name = request.rawHeaders[index].toLowerCase();
    counts.set(name, (counts.get(name) ?? 0) + 1);
  }
  // Node may discard/join duplicates before Spring gets to inspect them.
  return (
    counts.get('host') === 1 && (counts.get('origin') ?? 0) <= 1 && (counts.get('sec-fetch-site') ?? 0) <= 1
  );
}

/** A proxy must check its caller's socket: Spring can only see the proxy's socket. */
export function loopbackDevelopmentServer(): Plugin {
  return {
    name: 'velocity-loopback-development-server',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        if (!isLoopbackPeer(request.socket.remoteAddress) || !hasUnambiguousBoundaryHeaders(request)) {
          response.writeHead(403, { 'Content-Type': 'text/plain' });
          response.end('The Velocity development server requires an unambiguous loopback connection.');
          return;
        }
        // Vite's proxy drops chunked DELETE/OPTIONS bodies. Fail before dispatch instead.
        if (['DELETE', 'OPTIONS'].includes(request.method ?? '') && request.headers['transfer-encoding']) {
          response.writeHead(400, { 'Content-Type': 'text/plain' });
          response.end('Send DELETE/OPTIONS bodies with Content-Length through the development server.');
          return;
        }
        next();
      });
    },
  };
}

export function operatorDevelopmentProxy(port = '8090'): Record<string, ProxyOptions> {
  if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
    throw new Error('VELOCITY_DEV_BACKEND_PORT must be a port between 1 and 65535.');
  }
  const options: ProxyOptions = {
    target: `http://127.0.0.1:${Number(port)}`,
    // Preserve the browser's authority and origin for LocalOperatorFilter.
    changeOrigin: false,
    xfwd: false,
    ws: false,
  };
  return { '^/api(?:/|$|\\?)': { ...options }, '^/velocity/hello(?:$|\\?)': { ...options } };
}
