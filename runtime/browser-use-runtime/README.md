# Browser Use runtime

Separately installed runtime for Velocity's browser tools. Java (`velocity-browser`) starts one copy per
task execution over stdio MCP; nothing here chooses a model, authorizes a tool or persists state.

| File | Role |
| --- | --- |
| `launch.cjs` | Trusted launcher. Provisions a fresh Chromium with Playwright, installs the network hook, then starts the Python bridge over loopback CDP. |
| `network-guard.cjs` | Forwards every request, redirect, popup, frame, worker and WebSocket to Java's `BrowserNetworkGuard` and blocks anything not explicitly allowed. Contains no destination rules of its own. |
| `server.py` | Browser Use 0.13.10 bridge exposing `browser_navigate`, `browser_state`, `browser_click`, `browser_type`, `browser_scroll`, `browser_navigate_back`, `browser_press_key`, `browser_select_option`, `browser_tabs`, `browser_wait_for`, `browser_close`, plus private page-reading and credential-fill operations used only by Java. |
| `rendered_content.js` | Shared rendered-content reader. Excluded from Prettier on purpose — keep its layout. |
| `requirements.txt`, `package.json`, `package-lock.json` | Pinned Python and Node (Playwright) dependencies. |
| `setup.ps1` | Creates `.venv` and installs the Python requirements on Windows. |

## Install

Python 3.11+ and Node.js 22.12+, from the repository root:

```powershell
.\runtime\browser-use-runtime\setup.ps1 -Python python
npm ci --prefix runtime/browser-use-runtime
npm --prefix runtime/browser-use-runtime run install-browser
```

On Linux/macOS create `.venv` with `python3 -m venv` and `pip install -r requirements.txt`, and use
`install-browser-with-deps` to add Chromium's system packages. Install as the OS account that runs
Velocity. Then point Velocity at the interpreter:

```properties
AGENT_BROWSER_PYTHON_EXECUTABLE=C:/path/to/velocity/runtime/browser-use-runtime/.venv/Scripts/python.exe
```

A virtual environment cannot be moved; recreate it instead.

## Behavior

- Each session uses a fresh, in-memory incognito context; cookies, storage and caches never touch
  disk. The disk profile only provisions CDP.
- No model keys, `.env`, telemetry, cloud sync, extensions, downloads or recordings. Chromium logs
  go to the OS null device.
- Password and file inputs are not available to ordinary typing. Credential filling uses a private
  path that Java calls only after verifying the target element and receiver origin.
- Failed or uncertain actions are never replayed; process loss ends the session.

See [docs/browser.md](../../docs/browser.md) for configuration, tools and the network boundary.
