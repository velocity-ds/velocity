'use strict';

// Trusted launcher only. The model cannot select a process, profile, or CDP endpoint.
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');

const PLAYWRIGHT_VERSION = '1.64.0-alpha-1790635538000';

function parseArguments(argv) {
  const options = { headless: true, maxObservationChars: 24576 };
  const seen = new Set();
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index],
      value = argv[index + 1];
    if (
      !['--python', '--headless', '--max-observation-chars'].includes(name) ||
      seen.has(name) ||
      value === undefined ||
      value.length === 0
    )
      throw new Error('Invalid browser launcher options');
    seen.add(name);
    if (name === '--python') options.python = value;
    if (name === '--headless') {
      if (!['true', 'false'].includes(value)) throw new Error('Invalid headless option');
      options.headless = value === 'true';
    }
    if (name === '--max-observation-chars') {
      if (!/^[1-9][0-9]*$/.test(value)) throw new Error('Invalid observation limit');
      options.maxObservationChars = Number(value);
      if (!Number.isSafeInteger(options.maxObservationChars)) throw new Error('Invalid observation limit');
    }
  }
  if (!options.python) throw new Error('A Python executable is required');
  return options;
}

function requireNodeVersion(version) {
  const [major, minor] = version.split('.').map(Number);
  if (!Number.isInteger(major) || !Number.isInteger(minor) || major < 22 || (major === 22 && minor < 12))
    throw new Error('Node 22.12 or newer is required');
}

function operatorPath(value, fallback) {
  if (value !== undefined && (!path.isAbsolute(value) || !value))
    throw new Error('Runtime paths must be absolute');
  return value || fallback;
}

function childEnvironment(source, sessionDirectory, cdpUrl, maxObservationChars, browserContextId) {
  const environment = {};
  const remainingMillis = source.VELOCITY_BROWSER_TASK_REMAINING_MILLIS ?? '9223372036854775807';
  if (!/^[1-9][0-9]*$/.test(remainingMillis) || BigInt(remainingMillis) > 9223372036854775807n)
    throw new Error('Invalid trusted task deadline');
  const configuredSourceLimit = source.VELOCITY_BROWSER_MAX_SOURCE_CHARS;
  const sourceLimit =
    configuredSourceLimit === undefined
      ? Math.max(maxObservationChars, 500000)
      : Number(configuredSourceLimit);
  if (
    (configuredSourceLimit !== undefined && !/^[1-9][0-9]*$/.test(configuredSourceLimit)) ||
    !Number.isSafeInteger(sourceLimit) ||
    sourceLimit < Math.max(maxObservationChars, 500000) ||
    sourceLimit > 2147483647
  )
    throw new Error('Invalid trusted source capture limit');
  const retained = new Set(['PATH', 'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'LANG', 'LC_ALL', 'LC_CTYPE']);
  for (const [name, value] of Object.entries(source)) {
    if (retained.has(name.toUpperCase())) environment[name] = value;
  }
  return {
    ...environment,
    VELOCITY_BROWSER_USE_CDP_URL: cdpUrl,
    VELOCITY_BROWSER_USE_MAX_OBSERVATION_CHARS: String(maxObservationChars),
    VELOCITY_BROWSER_MAX_SOURCE_CHARS: String(sourceLimit),
    VELOCITY_BROWSER_TASK_REMAINING_MILLIS: remainingMillis,
    ...(browserContextId ? { VELOCITY_BROWSER_USE_CONTEXT_ID: browserContextId } : {}),
    BROWSER_USE_CONFIG_DIR: path.join(sessionDirectory, 'browser-use-config'),
    XDG_CACHE_HOME: path.join(sessionDirectory, 'browser-use-cache'),
    XDG_CONFIG_HOME: path.join(sessionDirectory, 'browser-use-config'),
    BROWSER_USE_SETUP_LOGGING: 'false',
    // Browser Use imports dotenv from installed package paths; an isolated cwd
    // alone does not stop that library from searching ancestor .env files.
    PYTHON_DOTENV_DISABLED: '1',
    BROWSER_USE_LOGGING_LEVEL: 'critical',
    CDP_LOGGING_LEVEL: 'CRITICAL',
    ANONYMIZED_TELEMETRY: 'false',
    BROWSER_USE_CLOUD_SYNC: 'false',
    BROWSER_USE_VERSION_CHECK: 'false',
    BROWSER_USE_DISABLE_EXTENSIONS: 'true',
    BH_TELEMETRY: '0',
  };
}

async function readCdpUrl(
  profileDirectory,
  alive,
  read = fs.readFileSync,
  pause = () => new Promise((resolve) => setTimeout(resolve, 50)),
) {
  const portFile = path.join(profileDirectory, 'DevToolsActivePort');
  while (alive()) {
    try {
      const [port, endpoint] = read(portFile, 'utf8').trim().split(/\r?\n/);
      if (
        /^[1-9][0-9]{0,4}$/.test(port) &&
        Number(port) <= 65535 &&
        /^\/devtools\/browser\/[a-zA-Z0-9-]+$/.test(endpoint)
      )
        return `ws://127.0.0.1:${port}${endpoint}`;
      throw new Error('Invalid browser endpoint');
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    await pause();
  }
  throw new Error('The browser endpoint is unavailable');
}

async function launch(argv = process.argv.slice(2)) {
  requireNodeVersion(process.versions.node);
  const options = parseArguments(argv);
  if (!process.env.VELOCITY_BROWSER_GUARD_URL || !process.env.VELOCITY_BROWSER_GUARD_TOKEN)
    throw new Error('The trusted browser guard is required');
  const sessionDirectory = path.resolve(process.cwd());
  if (fs.existsSync(path.join(sessionDirectory, '.env')))
    throw new Error('The session directory must not contain developer settings');
  const moduleDirectory = operatorPath(
    process.env.VELOCITY_BROWSER_USE_PLAYWRIGHT_MODULE,
    path.join(__dirname, 'node_modules', 'playwright'),
  );
  const hookPath = operatorPath(
    process.env.VELOCITY_BROWSER_USE_NETWORK_HOOK,
    path.join(__dirname, 'network-guard.cjs'),
  );
  if (
    JSON.parse(fs.readFileSync(path.join(moduleDirectory, 'package.json'), 'utf8')).version !==
    PLAYWRIGHT_VERSION
  )
    throw new Error('The locked Playwright installation is required');
  const { chromium } = require(moduleDirectory);
  const installGuard = require(hookPath).default;
  if (typeof installGuard !== 'function') throw new Error('The trusted browser hook is unavailable');

  const profileDirectory = path.join(sessionDirectory, 'chromium-profile');
  fs.mkdirSync(profileDirectory); // A stale profile must never be reused.
  const browserEnvironment = childEnvironment(process.env, sessionDirectory, '', options.maxObservationChars);
  delete browserEnvironment.VELOCITY_BROWSER_USE_CDP_URL;
  // Windows Chromium can otherwise persist page console/errors in chrome_debug.log.
  browserEnvironment.CHROME_LOG_FILE = process.platform === 'win32' ? 'NUL' : '/dev/null';
  // These OS paths locate the installed browser/cache, and carry no integration credentials.
  for (const name of [
    'LOCALAPPDATA',
    'USERPROFILE',
    'HOME',
    'PLAYWRIGHT_BROWSERS_PATH',
    'DISPLAY',
    'WAYLAND_DISPLAY',
  ]) {
    if (process.env[name] !== undefined) browserEnvironment[name] = process.env[name];
  }

  let context,
    provisioningContext,
    python,
    ending = false;
  const close = async (code = 0) => {
    if (ending) return;
    ending = true;
    process.stdin.unpipe();
    python?.stdin.end();
    const force = setTimeout(() => {
      python?.kill('SIGKILL');
      process.exit(code);
    }, 5000);
    try {
      await context?.close();
    } catch {
      /* Process exit also releases the owned browser. */
    }
    try {
      await provisioningContext?.close();
    } catch {
      /* Browser may already be closed. */
    }
    python?.kill();
    clearTimeout(force);
    process.exit(code);
  };
  process.once('SIGTERM', () => void close());
  process.once('SIGINT', () => void close());
  process.stdout.on('error', () => void close(1));

  try {
    provisioningContext = await chromium.launchPersistentContext(profileDirectory, {
      timeout: 0,
      headless: options.headless,
      acceptDownloads: false,
      serviceWorkers: 'allow',
      permissions: [],
      env: browserEnvironment,
      args: [
        '--remote-debugging-port=0',
        '--remote-debugging-address=127.0.0.1',
        '--disable-logging',
        '--log-level=3',
      ],
    });
    if (!provisioningContext.browser()) throw new Error('A browser-level policy connection is required');
    // The disk profile provisions CDP only. All browsing uses an owned incognito
    // context so cookies, response cache and page storage stay in memory.
    context = await provisioningContext
      .browser()
      .newContext({ acceptDownloads: false, serviceWorkers: 'allow', permissions: [] });
    const blankPage = await context.newPage();
    for (const page of provisioningContext.pages()) await page.close();
    // Install the trusted request, redirect, popup, worker and WebSocket guard.
    // The first page remains blank until every policy handler is installed.
    if (blankPage.url() !== 'about:blank') throw new Error('A blank isolated browser is required');
    await installGuard({ page: blankPage });
    const identityChannel = await context.newCDPSession(blankPage);
    const browserContextId = (await identityChannel.send('Target.getTargetInfo')).targetInfo.browserContextId;
    await identityChannel.detach();
    if (typeof browserContextId !== 'string' || !/^[a-fA-F0-9]{32}$/.test(browserContextId))
      throw new Error('An owned private browser context is required');
    const cdpUrl = await readCdpUrl(
      profileDirectory,
      () => provisioningContext.browser().isConnected() && !ending,
    );
    python = spawn(options.python, ['-I', '-u', path.join(__dirname, 'server.py')], {
      cwd: sessionDirectory,
      env: childEnvironment(
        process.env,
        sessionDirectory,
        cdpUrl,
        options.maxObservationChars,
        browserContextId,
      ),
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
      shell: false,
    });
    python.on('error', () => {
      process.stderr.write('Browser Use runtime could not start.\n');
      void close(1);
    });
    python.on('exit', (code) => void close(code === 0 ? 0 : 1));
    python.stdin.on('error', () => void close(1));
    // Raw library diagnostics may contain page values. Retain only fixed failures.
    python.stderr.resume();
    python.stdout.pipe(process.stdout);
    process.stdin.once('end', () => void close());
    process.stdin.once('error', () => void close(1));
    process.stdin.pipe(python.stdin);
    context.on('close', () => void close());
  } catch {
    process.stderr.write('Browser Use runtime could not start.\n');
    await close(1);
  }
}

module.exports = { parseArguments, requireNodeVersion, childEnvironment, readCdpUrl, launch };
if (require.main === module)
  launch().catch(() => {
    process.stderr.write('Browser Use runtime configuration is invalid.\n');
    process.exit(1);
  });
