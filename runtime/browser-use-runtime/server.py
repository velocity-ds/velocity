"""Owned Browser Use DOM/actions behind Velocity's Java authorization boundary."""

import asyncio
from contextlib import redirect_stderr, redirect_stdout
from importlib.metadata import version
import ipaddress
import json
import logging
import math
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

PINNED_VERSION = "0.13.10"
PRIVATE_TOOL = "browser_fill_private"
PRIVATE_READ_TOOL = "velocity_read_page"
PRIVATE_SCREENSHOT_TOOL = "velocity_screenshot"
PRIVATE_TOOLS = {PRIVATE_TOOL, PRIVATE_READ_TOOL, PRIVATE_SCREENSHOT_TOOL}
CONTENT_READ = Path(__file__).with_name("rendered_content.js").read_text(encoding="utf-8")
CONTENT_STABLE_SECONDS = 0.75
CONTENT_POLL_SECONDS = 0.1
CONTENT_COVERAGE = {
    "ok": "The selected rendered content was captured after settling. Hidden content, attachments and images are not included.",
    "partial": "Available rendered content was captured; the content named below was not read.",
    "truncated": "The selected rendered content reached the configured text or DOM traversal limit.",
    "missing": "No readable rendered content was identified after the page settled. Page navigation text was excluded.",
    "blocked": "The page requires sign-in or browser verification, or denies access. No content was captured.",
}
TASK_TIMEOUT_EVENTS = (
    "NavigateToUrlEvent",
    "ClickElementEvent",
    "TypeTextEvent",
    "ScrollEvent",
    "SwitchTabEvent",
    "CloseTabEvent",
    "BrowserStateRequestEvent",
    "GoBackEvent",
    "SendKeysEvent",
    "SelectDropdownOptionEvent",
    "BrowserStartEvent",
    "BrowserLaunchEvent",
    "BrowserConnectedEvent",
    "TabCreatedEvent",
    "TabClosedEvent",
    "AgentFocusChangedEvent",
    "NavigationStartedEvent",
    "NavigationCompleteEvent",
    "BrowserErrorEvent",
    "BrowserReconnectingEvent",
    "BrowserReconnectedEvent",
)


def task_timeout_seconds(remaining_millis):
    if type(remaining_millis) is not int or not 1 <= remaining_millis <= 2**63 - 1:
        raise ValueError("A trusted remaining task deadline is required")
    # Legacy direct callers have no task deadline. Do not round Java's sentinel
    # through float seconds: converting it back would exceed signed long range.
    if remaining_millis == 2**63 - 1:
        return None
    return remaining_millis / 1000


def configure_task_timeouts(remaining_millis):
    seconds = task_timeout_seconds(remaining_millis)
    if seconds is None:
        return None
    for event in TASK_TIMEOUT_EVENTS:
        os.environ["TIMEOUT_" + event] = str(seconds)
    os.environ["BROWSER_USE_CDP_TIMEOUT_S"] = str(seconds)
    os.environ["BROWSER_USE_ACTION_TIMEOUT_S"] = str(seconds)
    return seconds


NAVIGATION_KEYS = {
    "enter",
    "return",
    "escape",
    "esc",
    "tab",
    "shift+tab",
    "up",
    "down",
    "left",
    "right",
    "arrowup",
    "arrowdown",
    "arrowleft",
    "arrowright",
}
ACTIVE_ELEMENT_READ = """() => {
  let element = document.activeElement;
  for (let depth = 0; depth < 32; depth++) {
    if (!element) return JSON.stringify({restricted:true});
    if (element.shadowRoot && element.shadowRoot.activeElement) {
      element = element.shadowRoot.activeElement; continue;
    }
    const tag = element.localName;
    if (tag === 'iframe' || tag === 'frame') {
      try {
        const child = element.contentDocument;
        if (!child) return JSON.stringify({restricted:true});
        element = child.activeElement; continue;
      } catch (_) { return JSON.stringify({restricted:true}); }
    }
    return JSON.stringify({restricted:tag === 'input' && ['password','file'].includes(element.type)
      || tag.includes('-') && !element.shadowRoot});
  }
  return JSON.stringify({restricted:true});
}"""
PRIVATE_FOCUS_READ = """function() {
  let active = document.activeElement;
  for (let depth = 0; depth < 32 && active && active.shadowRoot && active.shadowRoot.activeElement; depth++)
    active = active.shadowRoot.activeElement;
  return {active:this === active,connected:this.isConnected,origin:location.origin,url:location.href,
    main_frame:window === window.top,tag:this.localName,type:this.type || '',
    disabled:this.disabled === true,read_only:this.readOnly === true};
}"""
INDEX = {"type": "integer", "minimum": 1}
TEXT = {"type": "string", "maxLength": 8192}
URL = {"type": "string", "minLength": 1, "maxLength": 8192}
TOOL_SCHEMAS = {
    "browser_navigate": ({"url": URL}, ["url"]),
    "browser_state": ({}, []),
    "browser_click": ({"index": INDEX}, ["index"]),
    "browser_type": ({"index": INDEX, "text": TEXT}, ["index", "text"]),
    "browser_scroll": ({"direction": {"type": "string", "enum": ["up", "down"]}}, ["direction"]),
    "browser_navigate_back": ({}, []),
    "browser_press_key": ({"key": {"type": "string", "minLength": 1, "maxLength": 128}}, ["key"]),
    "browser_select_option": ({"index": INDEX, "text": TEXT}, ["index", "text"]),
    "browser_tabs": (
        {
            "action": {"type": "string", "enum": ["list", "select", "new", "close"]},
            "page_id": {"type": "string", "minLength": 1, "maxLength": 512},
            "url": URL,
        },
        ["action"],
    ),
    "browser_wait_for": ({"time": {"type": "number", "minimum": 0, "maximum": 300}}, ["time"]),
    "browser_close": ({}, []),
    PRIVATE_READ_TOOL: (
        {"max_chars": INDEX, "section": {"type": "string", "minLength": 1, "maxLength": 160}},
        ["max_chars"],
    ),
    PRIVATE_SCREENSHOT_TOOL: ({"full_page": {"type": "boolean"}, "max_bytes": INDEX}, ["max_bytes"]),
    PRIVATE_TOOL: (
        {
            "origin": URL,
            "fields": {
                "type": "array",
                "minItems": 1,
                "maxItems": 2,
                "items": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "index": INDEX,
                        "kind": {"type": "string", "enum": ["username", "password"]},
                        "value": TEXT,
                        "backend_node_id": INDEX,
                        "frame_id": {"type": "string", "minLength": 1, "maxLength": 512},
                        "target_id": {"type": "string", "minLength": 1, "maxLength": 512},
                    },
                    "required": ["index", "kind", "value", "backend_node_id", "frame_id", "target_id"],
                },
            },
        },
        ["origin", "fields"],
    ),
}


def validate_value(value, schema):
    expected = schema["type"]
    if expected == "string":
        if not isinstance(value, str) or not schema.get("minLength", 0) <= len(value) <= schema.get(
            "maxLength", 8192
        ):
            raise ValueError("Invalid string")
    elif expected in ("integer", "number"):
        if (
            type(value) not in ((int,) if expected == "integer" else (int, float))
            or not math.isfinite(value)
            or not schema.get("minimum", 0) <= value <= schema.get("maximum", 2**31 - 1)
        ):
            raise ValueError("Invalid number")
    elif expected == "boolean":
        if type(value) is not bool:
            raise ValueError("Invalid boolean")
    elif expected == "array":
        if not isinstance(value, list) or not schema["minItems"] <= len(value) <= schema["maxItems"]:
            raise ValueError("Invalid array")
        for item in value:
            validate_value(item, schema["items"])
    elif expected == "object":
        if not isinstance(value, dict) or set(value) != set(schema["required"]):
            raise ValueError("Invalid object")
        for name, item in value.items():
            validate_value(item, schema["properties"][name])
    if "enum" in schema and value not in schema["enum"]:
        raise ValueError("Invalid enum")


def validate_host(host):
    # Defense-in-depth mirror of BrowserDestinationPolicy.validateHost in Java, which is the
    # authority for destination rules and has already vetted every URL this bridge receives.
    # This copy must never accept a host that Java rejects; keep the two in step.
    if not host or len(host) > 253 or "%" in host or host.endswith("."):
        raise ValueError("Invalid host")
    if ":" in host:
        ipaddress.IPv6Address(host)
        return
    labels = host.split(".")
    numeric = re.fullmatch(r"[0-9.]+", host) or re.fullmatch(
        r"(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+))*", host
    )
    last = labels[-1]
    if not numeric and (re.fullmatch(r"[0-9]+", last) or re.fullmatch(r"0x[0-9a-f]*", last)):
        raise ValueError("Ambiguous numeric-suffix host")
    if numeric and (
        len(labels) != 4
        or any(not re.fullmatch(r"0|[1-9][0-9]{0,2}", label) or int(label) > 255 for label in labels)
    ):
        raise ValueError("Noncanonical numeric host")
    if any(not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in labels):
        raise ValueError("Invalid host label")


def validate_http_url(url):
    parsed = urlsplit(url)
    if (
        parsed.scheme not in ("http", "https")
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or "\\" in url
        or any(
            character.isspace() or ord(character) < 32 or 127 <= ord(character) <= 159 for character in url
        )
        or parsed.netloc.endswith(":")
    ):
        raise ValueError("Absolute HTTP(S) URL required")
    validate_host(parsed.hostname)
    if parsed.port == 0:
        raise ValueError("Invalid port")
    return parsed


def origin_of(url):
    # Canonical form of BrowserDestinationPolicy.origin; Java is the owner of this rule.
    parsed = validate_http_url(url)
    host = parsed.hostname.encode("idna").decode("ascii").lower()
    if ":" in host:
        host = "[" + host + "]"
    port = parsed.port
    suffix = "" if port is None or port == {"http": 80, "https": 443}[parsed.scheme] else ":" + str(port)
    return parsed.scheme + "://" + host + suffix


def utf16_prefix(value, maximum_units):
    # Java limits String.length() in UTF-16 units. Redaction may expand a title
    # after its original DOM bound; never split a supplementary character.
    return value.encode("utf-16-le", errors="surrogatepass")[: maximum_units * 2].decode(
        "utf-16-le", errors="ignore"
    )


def validate_arguments(name, arguments):
    if name not in TOOL_SCHEMAS or not isinstance(arguments, dict):
        raise ValueError("Unsupported tool")
    properties, required = TOOL_SCHEMAS[name]
    if not set(required) <= set(arguments) <= set(properties):
        raise ValueError("Unexpected arguments")
    for key, value in arguments.items():
        validate_value(value, properties[key])
    if name == "browser_navigate":
        validate_http_url(arguments["url"])
    elif name == "browser_tabs":
        action = arguments["action"]
        expected = {"list": {"action"}, "select": {"action", "page_id"}, "close": {"action", "page_id"}}
        if (
            action in expected
            and set(arguments) != expected[action]
            or action == "new"
            and "page_id" in arguments
        ):
            raise ValueError("Invalid tab action")
        if "url" in arguments:
            validate_http_url(arguments["url"])
    elif name == PRIVATE_TOOL:
        if arguments["origin"] != origin_of(arguments["origin"]):
            raise ValueError("An exact canonical origin is required")
        kinds = [field["kind"] for field in arguments["fields"]]
        if len(kinds) != len(set(kinds)) or any(not field["value"] for field in arguments["fields"]):
            raise ValueError("Distinct nonempty private fields required")
    elif name == PRIVATE_READ_TOOL and "section" in arguments:
        if not arguments["section"].strip() or not any(
            character.isalnum() for character in arguments["section"]
        ):
            raise ValueError("A plain section label is required")


def validate_cdp_url(value):
    parsed = urlsplit(value)
    if (
        parsed.scheme != "ws"
        or parsed.hostname != "127.0.0.1"
        or not parsed.port
        or parsed.username is not None
        or parsed.password is not None
        or not re.fullmatch(r"/devtools/browser/[a-zA-Z0-9-]+", parsed.path)
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("An owned loopback browser endpoint is required")
    return value


def native_root(dom):
    node = getattr(getattr(dom, "_root", None), "original_node", None)
    seen = set()
    while node is not None and getattr(node, "parent_node", None) is not None:
        if id(node) in seen:
            return None
        seen.add(id(node))
        node = node.parent_node
    return node


def root_identity(dom):
    # The native root belongs to the top document. Descendant frames may share
    # its target, but cannot share its frame ID. Missing identity fails closed.
    node = native_root(dom)
    frame_id, target_id = getattr(node, "frame_id", None), getattr(node, "target_id", None)
    return (frame_id, target_id) if frame_id and target_id else None


def effective_frame(node, identity):
    current, seen = node, set()
    while current is not None:
        if id(current) in seen:
            return None
        seen.add(id(current))
        tag = (getattr(current, "tag_name", None) or "").lower()
        # An iframe owner's frameId identifies its content, not the owner itself.
        if current is not node and tag in ("iframe", "frame"):
            return getattr(current, "frame_id", None) or None
        if tag not in ("iframe", "frame") and getattr(current, "frame_id", None):
            return current.frame_id
        parent = getattr(current, "parent_node", None)
        if parent is None:
            return (
                identity[0]
                if identity is not None and current.target_id == identity[1] and node.target_id == identity[1]
                else None
            )
        current = parent
    return None


def fingerprint(node, identity):
    return (node.backend_node_id, effective_frame(node, identity), node.target_id)


def is_main_frame(node, identity):
    return identity is not None and (effective_frame(node, identity), node.target_id) == identity


def node_metadata(index, node, identity):
    attributes = node.attributes or {}
    result = {
        "index": index,
        "backend_node_id": node.backend_node_id,
        "frame_id": effective_frame(node, identity) or "",
        "target_id": node.target_id or "",
        "main_frame": is_main_frame(node, identity),
        "tag": (node.tag_name or "").lower(),
        "type": str(attributes.get("type", "")).lower(),
    }
    if attributes.get("href"):
        href = str(attributes["href"])
        if len(href) <= 8192:
            result["href"] = href
    label = attributes.get("aria-label") or attributes.get("placeholder") or attributes.get("name")
    if not label and getattr(node, "ax_node", None):
        label = node.ax_node.name
    if label:
        result["label"] = str(label)[:512]
    return result


class BrowserRuntime:
    def __init__(
        self,
        browser,
        tools,
        action_model,
        maximum_characters=24576,
        owned_context_id=None,
        maximum_source_characters=None,
    ):
        self.browser, self.tools, self.action_model = browser, tools, action_model
        self.maximum_characters = maximum_characters
        self.maximum_source_characters = (
            max(maximum_characters, 500000)
            if maximum_source_characters is None
            else maximum_source_characters
        )
        if (
            type(self.maximum_source_characters) is not int
            or not 1 <= self.maximum_source_characters <= 2**31 - 1
        ):
            raise ValueError("Invalid source capture limit")
        self.owned_context_id = owned_context_id
        self.observed_indices, self.observed_nodes = set(), {}
        self.private_values = set()
        self.closed = False
        self.lock = asyncio.Lock()
        self.operation_deadline = None

    def invalidate_state(self):
        self.observed_indices.clear()
        self.observed_nodes.clear()

    async def close(self):
        self.invalidate_state()
        self.private_values.clear()
        if not self.closed:
            self.closed = True
            await self.browser.kill()

    def redact(self, value):
        for secret in sorted(self.private_values, key=len, reverse=True):
            value = value.replace(secret, "[REDACTED]")
        return value

    def redact_display(self, payload):
        # Identity and policy metadata must survive values such as "type" or
        # "input". Java separately protects raw URLs/hrefs before durability.
        for tab in payload.get("tabs", []):
            tab["title"] = self.redact(tab["title"])[:4096]
        return payload

    async def snapshot(self):
        await self.assert_owned_focus()
        state = await self.browser.get_browser_state_summary(include_screenshot=False, cached=False)
        if state.state_error or state.dom_state is None:
            raise ValueError("Browser state unavailable")
        page = await self.browser.get_current_page()
        if page is None:
            raise ValueError("Current page unavailable")
        # Fixed trusted read: no model-provided expression is ever evaluated.
        metadata = json.loads(
            await page.evaluate(
                "() => JSON.stringify({url:location.href,title:document.title,origin:location.origin})"
            )
        )
        if set(metadata) != {"url", "title", "origin"} or not all(
            isinstance(value, str) for value in metadata.values()
        ):
            raise ValueError("Invalid page metadata")
        if metadata["url"] != state.url:
            raise ValueError("The page changed during observation")
        if len(metadata["url"]) > 8192:
            raise ValueError("The page URL exceeds the authority boundary")
        identity = root_identity(state.dom_state)
        if identity is None:
            root = native_root(state.dom_state)
            target_id = getattr(root, "target_id", None)
            if target_id and target_id == self.browser.agent_focus_target_id:
                session = await self.browser.get_or_create_cdp_session(target_id=target_id, focus=False)
                frame_tree = await session.cdp_client.send.Page.getFrameTree(session_id=session.session_id)
                frame = frame_tree["frameTree"]["frame"]
                if frame.get("id") and frame.get("url") == metadata["url"]:
                    identity = (frame["id"], target_id)
        return state, metadata, identity

    async def assert_owned_focus(self):
        session = await self.browser.get_or_create_cdp_session(target_id=None, focus=False)
        if self.owned_context_id is not None:
            info = await session.cdp_client.send.Target.getTargetInfo(session_id=session.session_id)
            if info["targetInfo"].get("browserContextId") != self.owned_context_id:
                raise ValueError("The focused page left its owned private context")

    async def guard_key(self, key):
        # Navigation remains available to leave or submit a private field. Other
        # keys cannot type, paste, copy, or modify an unobservable private focus.
        if key.strip().lower() in NAVIGATION_KEYS:
            return
        page = await self.browser.get_current_page()
        if page is None:
            raise ValueError("Current keyboard focus unavailable")
        focus = json.loads(await page.evaluate(ACTIVE_ELEMENT_READ))
        if set(focus) != {"restricted"} or type(focus["restricted"]) is not bool or focus["restricted"]:
            raise ValueError("Private keyboard focus unavailable")

    async def tabs(self):
        native_tabs = await self.browser.get_tabs()
        if len(native_tabs) > 100:
            raise ValueError("Too many tabs")
        if any(len(tab.url) > 8192 for tab in native_tabs):
            raise ValueError("A tab URL exceeds the authority boundary")
        if self.owned_context_id is not None:
            for tab in native_tabs:
                info = await self.browser.cdp_client.send.Target.getTargetInfo(
                    params={"targetId": tab.target_id}
                )
                if info["targetInfo"].get("browserContextId") != self.owned_context_id:
                    raise ValueError("A tab left its owned private context")
        return [{"page_id": str(tab.target_id), "url": tab.url, "title": tab.title} for tab in native_tabs]

    async def state(self):
        self.invalidate_state()
        state, metadata, identity = await self.snapshot()
        text = state.dom_state.llm_representation()
        nodes = dict(state.dom_state.selector_map)
        # Text cannot invent a node: intersect displayed IDs with native bindings.
        shown = {int(match) for match in re.findall(r"\[(\d+)\]<", text)}
        nodes = {index: node for index, node in nodes.items() if index in shown}
        nodes = {
            index: node for index, node in nodes.items() if node.target_id and effective_frame(node, identity)
        }
        if len(nodes) > 10000:
            raise ValueError("Too many elements")
        elements = [node_metadata(index, node, identity) for index, node in sorted(nodes.items())]
        text, title = self.redact(text), self.redact(metadata["title"])
        truncated = len(text) > self.maximum_characters or len(title) > 4096
        payload = {
            "status": "ok",
            "url": metadata["url"],
            "title": title[:4096],
            "text": text[: self.maximum_characters],
            "indices": sorted(nodes),
            "elements": elements,
            "truncated": truncated,
            "tabs": await self.tabs(),
        }
        # Redaction expansion is bounded before authority is retained.
        for element in payload["elements"]:
            if "label" in element:
                label = self.redact(element["label"])
                if len(label) > 512:
                    truncated = payload["truncated"] = True
                element["label"] = label[:512]
        for tab in payload["tabs"]:
            tab_title = self.redact(tab["title"])
            if len(tab_title) > 4096:
                truncated = payload["truncated"] = True
            tab["title"] = tab_title[:4096]
        if len(json.dumps(payload, ensure_ascii=True)) > 262144 + 12 * self.maximum_characters:
            payload["elements"] = []
            payload["indices"] = []
            truncated = payload["truncated"] = True
        if truncated:
            payload["indices"] = []
            payload["elements"] = []
        else:
            self.observed_nodes = nodes
            self.observed_indices = set(nodes)
        return payload

    async def read_page(self, arguments):
        self.invalidate_state()
        maximum = arguments["max_chars"]
        if maximum > self.maximum_source_characters:
            raise ValueError("Source capture limit exceeded")
        observed, previous, stable_since, expected_url = None, None, None, None
        loop = asyncio.get_running_loop()
        # The caller owns the only operation deadline, including page reads and settling.
        await self.assert_owned_focus()
        target = self.browser.agent_focus_target_id
        if not target:
            raise ValueError("An owned current page is required")
        while True:
            await self.assert_owned_focus()
            page = await self.browser.get_current_page()
            if (
                page is None
                or self.browser.agent_focus_target_id != target
                or (await page.get_target_info()).get("targetId") != target
            ):
                raise ValueError("The focused page changed during content capture")
            value = json.loads(await page.evaluate(CONTENT_READ, arguments))
            await self.assert_owned_focus()
            if self.browser.agent_focus_target_id != target:
                raise ValueError("The focused page changed during content capture")
            if (
                not isinstance(value, dict)
                or set(value) != {"status", "url", "title", "text", "ready", "detail"}
                or any(
                    not isinstance(value[key], str) for key in ("status", "url", "title", "text", "detail")
                )
                or type(value["ready"]) is not bool
                or value["status"] not in CONTENT_COVERAGE
                or len(value["url"]) > 8192
                or len(value["title"]) > 4096
                or len(value["text"]) > maximum
                or len(value["detail"]) > 1000
            ):
                raise ValueError("Invalid rendered content observation")
            validate_http_url(value["url"])
            if expected_url is not None and value["url"] != expected_url:
                raise ValueError("The page changed during content capture")
            expected_url = value["url"]
            if (
                value["status"] in ("missing", "blocked")
                and value["text"]
                or value["status"] in ("ok", "partial", "truncated")
                and not value["text"].strip()
            ):
                raise ValueError("Invalid content coverage")
            observed = value
            if value["status"] == "blocked":
                break
            signature = (value["status"], value["text"])
            if value["ready"]:
                if signature != previous:
                    stable_since = loop.time()
                elif stable_since is not None and loop.time() - stable_since >= CONTENT_STABLE_SECONDS:
                    break
                previous = signature
            else:
                previous, stable_since = None, None
            await asyncio.sleep(CONTENT_POLL_SECONDS)
        status = observed["status"]
        text = self.redact(observed["text"])
        if len(text) > maximum:
            text, status = text[:maximum], "truncated"
        # The note names what was collapsed, embedded or still loading so the reader need not probe for it.
        detail = self.redact(observed["detail"]).strip()
        coverage = CONTENT_COVERAGE[status] + (" " + detail if detail else "")
        return {
            "status": "ok",
            "read_status": status,
            "url": observed["url"],
            "title": utf16_prefix(self.redact(observed["title"]), 4096),
            "text": text,
            "coverage": utf16_prefix(coverage, 2000),
        }

    async def screenshot(self, arguments):
        # Pixels of the owned current page only; the image goes to the requester, never into model context.
        await self.assert_owned_focus()
        target = self.browser.agent_focus_target_id
        if not target:
            raise ValueError("An owned current page is required")
        page = await self.browser.get_current_page()
        if page is None or (await page.get_target_info()).get("targetId") != target:
            raise ValueError("The focused page changed during capture")
        metadata = json.loads(
            await page.evaluate("() => JSON.stringify({url:location.href,title:document.title})")
        )
        if set(metadata) != {"url", "title"} or not all(
            isinstance(value, str) for value in metadata.values()
        ):
            raise ValueError("Invalid page metadata")
        if len(metadata["url"]) > 8192:
            raise ValueError("The page URL exceeds the authority boundary")
        validate_http_url(metadata["url"])
        session = await self.browser.get_or_create_cdp_session(target_id=target, focus=False)
        params = {"format": "png", "captureBeyondViewport": bool(arguments.get("full_page", False))}
        result = await session.cdp_client.send.Page.captureScreenshot(
            params=params, session_id=session.session_id
        )
        data = result.get("data") if isinstance(result, dict) else None
        if not isinstance(data, str) or not data:
            raise ValueError("Screenshot unavailable")
        # Base64 expands by 4/3; compare against the caller's byte ceiling before shipping the payload.
        if len(data) > (arguments["max_bytes"] + 2) // 3 * 4:
            raise ValueError("Screenshot exceeds the byte limit")
        await self.assert_owned_focus()
        if self.browser.agent_focus_target_id != target:
            raise ValueError("The focused page changed during capture")
        return {
            "status": "ok",
            "url": metadata["url"],
            "title": utf16_prefix(self.redact(metadata["title"]), 4096),
            "image_format": "png",
            "image_base64": data,
        }

    async def observed_node(self, index):
        if index not in self.observed_indices:
            raise ValueError("A current observed element is required")
        node = self.observed_nodes[index]
        # Cached lookup only: no refresh or reindex before an accepted action.
        if await self.browser.get_dom_element_by_index(index) is not node:
            self.invalidate_state()
            raise ValueError("The observed element binding is stale")
        if str((node.attributes or {}).get("type", "")).lower() in ("password", "file"):
            raise ValueError("Private and file fields are unavailable")
        return node

    async def act(self, action):
        model = self.action_model.model_validate(action)
        self.invalidate_state()
        options = {}
        if self.operation_deadline is not None:
            remaining = self.operation_deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise TimeoutError("The task deadline expired")
            options["action_timeout"] = remaining
        result = await self.tools.act(model, browser_session=self.browser, **options)
        if result.error:
            raise ValueError("Browser action failed")

    async def fill_private(self, arguments):
        self.invalidate_state()
        self.private_values.update(field["value"] for field in arguments["fields"])
        initial_page = None
        for field in arguments["fields"]:
            state, metadata, identity = await self.snapshot()
            current_page = (metadata["url"], identity)
            if (
                metadata["origin"] != arguments["origin"]
                or origin_of(metadata["url"]) != arguments["origin"]
                or identity is None
                or initial_page is not None
                and current_page != initial_page
            ):
                raise ValueError("Private page binding changed")
            initial_page = current_page
            expected = (field["backend_node_id"], field["frame_id"], field["target_id"])
            matches = [
                (index, node)
                for index, node in state.dom_state.selector_map.items()
                if fingerprint(node, identity) == expected
            ]
            if len(matches) != 1:
                raise ValueError("Private field binding changed")
            index, node = matches[0]
            attributes = node.attributes or {}
            tag, input_type = (node.tag_name or "").lower(), str(attributes.get("type", "text")).lower()
            allowed = (
                tag == "input" and input_type == "password"
                if field["kind"] == "password"
                else tag == "textarea" or tag == "input" and input_type in ("text", "email")
            )
            if (
                not is_main_frame(node, identity)
                or not allowed
                or "disabled" in attributes
                or "readonly" in attributes
            ):
                raise ValueError("Private field unavailable")
            if await self.browser.get_dom_element_by_index(index) is not node:
                raise ValueError("Private cache binding changed")
            await self.insert_private(node, field, metadata)
        return {"status": "ok"}

    async def insert_private(self, node, field, metadata):
        # Upstream sensitive input still emits one input event per character.
        # Use Browser Use's owned CDP session for one full-value insertion so page
        # handlers never observe secret prefixes while the field is being filled.
        session = await self.browser.cdp_client_for_node(node)
        send, session_id = session.cdp_client.send, session.session_id
        resolved = await send.DOM.resolveNode(
            params={"backendNodeId": node.backend_node_id}, session_id=session_id
        )
        object_id = resolved.get("object", {}).get("objectId")
        if not object_id:
            raise ValueError("Private field unavailable")
        await send.DOM.focus(params={"backendNodeId": node.backend_node_id}, session_id=session_id)
        # Select existing content without clearing it or emitting an empty input.
        await send.Input.dispatchKeyEvent(
            params={
                "type": "keyDown",
                "key": "a",
                "code": "KeyA",
                "windowsVirtualKeyCode": 65,
                "modifiers": 2,
                "commands": ["selectAll"],
            },
            session_id=session_id,
        )
        await send.Input.dispatchKeyEvent(
            params={"type": "keyUp", "key": "a", "code": "KeyA", "windowsVirtualKeyCode": 65, "modifiers": 2},
            session_id=session_id,
        )
        result = await send.Runtime.callFunctionOn(
            params={"objectId": object_id, "functionDeclaration": PRIVATE_FOCUS_READ, "returnByValue": True},
            session_id=session_id,
        )
        focus = result.get("result", {}).get("value")
        if (
            "exceptionDetails" in result
            or not isinstance(focus, dict)
            or set(focus)
            != {"active", "connected", "origin", "url", "main_frame", "tag", "type", "disabled", "read_only"}
            or focus["active"] is not True
            or focus["connected"] is not True
            or focus["main_frame"] is not True
            or focus["disabled"] is not False
            or focus["read_only"] is not False
            or focus["origin"] != metadata["origin"]
            or focus["url"] != metadata["url"]
        ):
            raise ValueError("Private focus binding changed")
        allowed = (
            focus["tag"] == "input" and focus["type"] == "password"
            if field["kind"] == "password"
            else focus["tag"] == "textarea" or focus["tag"] == "input" and focus["type"] in ("text", "email")
        )
        if not allowed:
            raise ValueError("Private focus field kind changed")
        # No value readback and no replay after any uncertain dispatch.
        await send.Input.insertText(params={"text": field["value"]}, session_id=session_id)

    async def change_tabs(self, arguments):
        action = arguments["action"]
        if action == "list":
            tabs = await self.tabs()
            truncated = any(len(self.redact(tab["title"])) > 4096 for tab in tabs)
            return self.redact_display({"status": "ok", "tabs": tabs, "truncated": truncated})
        self.invalidate_state()
        if action == "new":
            page = await self.browser.new_page("about:blank")
            page_id = (await page.get_target_info())["targetId"]
            # Creation acknowledges before the native attachment pool has always
            # published its page metadata. Settle that read without replaying it.
            await self.browser.get_or_create_cdp_session(target_id=page_id, focus=False)
        else:
            page_id = arguments["page_id"]
        tabs = await self.tabs()
        if action == "new":
            while page_id not in {tab["page_id"] for tab in tabs}:
                await asyncio.sleep(0.02)
                tabs = await self.tabs()
        if page_id not in {tab["page_id"] for tab in tabs}:
            raise ValueError("An owned page is required")
        if action == "close":
            if len(tabs) == 1:
                replacement = await self.browser.new_page("about:blank")
                replacement_id = (await replacement.get_target_info())["targetId"]
                await self.browser.get_or_create_cdp_session(target_id=replacement_id, focus=False)
                while replacement_id not in {tab["page_id"] for tab in tabs}:
                    await asyncio.sleep(0.02)
                    tabs = await self.tabs()
            if self.browser.agent_focus_target_id == page_id:
                survivors = [tab for tab in tabs if tab["page_id"] != page_id]
                if survivors:
                    suffix = survivors[0]["page_id"][-4:]
                    if sum(tab["page_id"].endswith(suffix) for tab in tabs) != 1:
                        raise ValueError("An unambiguous surviving page is required")
                    # Switch before destruction so native cache clearing settles
                    # on a real survivor instead of racing detached-focus recovery.
                    await self.act({"switch": {"tab_id": suffix}})
            await self.browser.close_page(page_id)
            # Native target removal and focus recovery follow closeTarget's
            # acknowledgement asynchronously. Settle those reads before success.
            while True:
                native_tabs = await self.browser.get_tabs()
                if page_id not in {str(tab.target_id) for tab in native_tabs}:
                    tabs = await self.tabs()
                    break
                await asyncio.sleep(0.02)
            if tabs and self.browser.agent_focus_target_id not in {tab["page_id"] for tab in tabs}:
                suffix = tabs[0]["page_id"][-4:]
                if sum(tab["page_id"].endswith(suffix) for tab in tabs) != 1:
                    raise ValueError("An unambiguous surviving page is required")
                await self.act({"switch": {"tab_id": suffix}})
            await self.browser.get_or_create_cdp_session(target_id=None, focus=False)
        else:
            suffix = page_id[-4:]
            if sum(tab["page_id"].endswith(suffix) for tab in tabs) != 1:
                raise ValueError("An unambiguous owned page is required")
            await self.act({"switch": {"tab_id": suffix}})
            if "url" in arguments:
                await self.assert_owned_focus()
                await self.browser.navigate_to(arguments["url"], new_tab=False)
        return {"status": "ok"}

    async def call(self, name, arguments, remaining_millis=None):
        validate_arguments(name, arguments)
        seconds = None if remaining_millis is None else task_timeout_seconds(remaining_millis)
        deadline = None if seconds is None else asyncio.get_running_loop().time() + seconds
        async with asyncio.timeout_at(deadline):
            async with self.lock:
                self.operation_deadline = deadline
                if deadline is not None:
                    remaining = int((deadline - asyncio.get_running_loop().time()) * 1000)
                    if remaining <= 0:
                        raise TimeoutError("The task deadline expired")
                    configure_task_timeouts(remaining)
                try:
                    return await self._call(name, arguments)
                finally:
                    self.operation_deadline = None

    async def _call(self, name, arguments):
        if name == "browser_close":
            await self.close()
            return {"status": "ok"}
        if self.closed:
            raise ValueError("Browser session closed")
        if name == "browser_state":
            return await self.state()
        if name == "browser_tabs":
            return await self.change_tabs(arguments)
        if name == PRIVATE_TOOL:
            return await self.fill_private(arguments)
        if name == PRIVATE_READ_TOOL:
            return await self.read_page(arguments)
        if name == PRIVATE_SCREENSHOT_TOOL:
            return await self.screenshot(arguments)
        await self.assert_owned_focus()
        if name == "browser_press_key":
            await self.guard_key(arguments["key"])
        if "index" in arguments:
            await self.observed_node(arguments["index"])
        if name == "browser_navigate":
            self.invalidate_state()
            # Single native dispatch avoids Tools' empty-DOM reload fallback.
            await self.browser.get_or_create_cdp_session(target_id=None, focus=False)
            await self.browser.navigate_to(arguments["url"], new_tab=False)
            return {"status": "ok"}
        if name == "browser_wait_for":
            self.invalidate_state()
            await asyncio.sleep(arguments["time"])
            return {"status": "ok"}
        actions = {
            "browser_click": lambda: {"click": {"index": arguments["index"]}},
            "browser_type": lambda: {
                "input": {"index": arguments["index"], "text": arguments["text"], "clear": True}
            },
            "browser_scroll": lambda: {"scroll": {"down": arguments["direction"] == "down", "pages": 1.0}},
            "browser_navigate_back": lambda: {"go_back": {}},
            "browser_press_key": lambda: {"send_keys": {"keys": arguments["key"]}},
            "browser_select_option": lambda: {
                "select_dropdown": {"index": arguments["index"], "text": arguments["text"]}
            },
        }
        await self.act(actions[name]())
        return {"status": "ok"}


async def serve():
    if version("browser-use") != PINNED_VERSION or Path(".env").exists():
        raise ValueError("Pinned isolated runtime required")
    cdp_url = validate_cdp_url(os.environ["VELOCITY_BROWSER_USE_CDP_URL"])
    maximum_characters = int(os.environ.get("VELOCITY_BROWSER_USE_MAX_OBSERVATION_CHARS", "24576"))
    if maximum_characters < 1:
        raise ValueError("Invalid observation limit")
    maximum_source_characters = int(
        os.environ.get("VELOCITY_BROWSER_MAX_SOURCE_CHARS", str(max(maximum_characters, 500000)))
    )
    if not 1 <= maximum_source_characters <= 2**31 - 1:
        raise ValueError("Invalid source capture limit")
    os.environ["PYTHON_DOTENV_DISABLED"] = "1"
    logging.disable(logging.CRITICAL)
    configure_task_timeouts(int(os.environ["VELOCITY_BROWSER_TASK_REMAINING_MILLIS"]))
    from browser_use.browser import BrowserSession
    from browser_use.browser.events import BrowserConnectedEvent
    from browser_use.actor.page import Page
    from browser_use.tools.service import Tools
    import mcp.server.stdio
    import mcp.types as types
    from mcp.server import Server

    context_id = os.environ["VELOCITY_BROWSER_USE_CONTEXT_ID"]
    if not re.fullmatch(r"[a-fA-F0-9]{32}", context_id):
        raise ValueError("An owned private browser context is required")

    class OwnedBrowserSession(BrowserSession):
        async def new_page(self, url=None):
            target_id = await self._cdp_create_new_page(url or "about:blank")
            return Page(self, target_id)

        async def _cdp_create_new_page(self, url="about:blank", background=False, new_window=False):
            parameters = {"url": url, "background": background, "browserContextId": context_id}
            if new_window:
                parameters["newWindow"] = True
            return (await self.cdp_client.send.Target.createTarget(params=parameters))["targetId"]

    browser = OwnedBrowserSession(
        cdp_url=cdp_url,
        is_local=False,
        use_cloud=False,
        keep_alive=True,
        user_data_dir=Path.cwd() / "browser-use-profile",
        storage_state=None,
        downloads_path=Path.cwd() / "downloads",
        accept_downloads=False,
        auto_download_pdfs=False,
        enable_default_extensions=False,
        permissions=[],
        captcha_solver=False,
        highlight_elements=False,
        record_har_path=None,
        record_video_dir=None,
    )
    tools = Tools()

    action_model = tools.registry.create_action_model(
        include_actions=["click", "input", "scroll", "go_back", "send_keys", "select_dropdown", "switch"]
    )
    runtime = BrowserRuntime(
        browser, tools, action_model, maximum_characters, context_id, maximum_source_characters
    )
    server = Server("velocity-browser-use", version=PINNED_VERSION)

    async def list_tools(_context, _params):
        return types.ListToolsResult(
            tools=[
                types.Tool(
                    name=name,
                    description="Velocity-owned Browser Use " + name.removeprefix("browser_") + ".",
                    input_schema={
                        "type": "object",
                        "properties": properties,
                        "required": required,
                        "additionalProperties": False,
                    },
                )
                for name, (properties, required) in TOOL_SCHEMAS.items()
                if name not in PRIVATE_TOOLS
            ]
        )

    with open(os.devnull, "w", encoding="utf-8") as quiet:

        async def call_tool(_context, params):
            try:
                arguments = dict(params.arguments or {})
                remaining_millis = arguments.pop("_velocity_remaining_millis")
                result = await runtime.call(params.name, arguments, remaining_millis)
                return types.CallToolResult(
                    content=[types.TextContent(type="text", text=json.dumps(result, ensure_ascii=True))]
                )
            except Exception:
                return types.CallToolResult(
                    content=[types.TextContent(type="text", text='{"status":"error"}')], is_error=True
                )

        server.add_request_handler("tools/list", types.PaginatedRequestParams, list_tools)
        server.add_request_handler("tools/call", types.CallToolRequestParams, call_tool)
        try:
            with redirect_stdout(quiet), redirect_stderr(quiet):
                # The endpoint is already owned and guarded. Connect directly so
                # BrowserSession.start's separate 15-second connect wrapper is
                # not an earlier task cutoff; Java cancellation owns startup.
                await browser.attach_all_watchdogs()
                await browser.connect(cdp_url=cdp_url)
                await browser.event_bus.dispatch(BrowserConnectedEvent(cdp_url=cdp_url))
                await browser.start()
            # Capture the MCP wire first, then discard prints from all handlers.
            async with mcp.server.stdio.stdio_server() as (read_stream, write_stream):
                with redirect_stdout(quiet), redirect_stderr(quiet):
                    await server.run(read_stream, write_stream, server.create_initialization_options())
        finally:
            with redirect_stdout(quiet), redirect_stderr(quiet):
                await runtime.close()


if __name__ == "__main__":
    try:
        asyncio.run(serve())
    except Exception:
        sys.stderr.write("Browser Use runtime is unavailable.\n")
        sys.exit(1)
