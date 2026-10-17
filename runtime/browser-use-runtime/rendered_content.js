(configuration) => {
  // Trusted, fixed read of rendered content. Layout visibility deliberately
  // includes text below the viewport; hidden/collapsed content is never expanded.
  const maximumCharacters = configuration.max_chars;
  const section = configuration.section || '';
  const hiddenByClosedDetails = element => {
    for (let parent = element; parent; parent = parent.parentElement) {
      if (parent.tagName !== 'DETAILS' || parent.open || parent === element) continue;
      const summary = Array.from(parent.children).find(child => child.tagName === 'SUMMARY');
      if (!summary || !summary.contains(element)) return true;
    }
    return false;
  };
  const cssHidden = element => {
    const style = getComputedStyle(element);
    return style.visibility === 'hidden' || style.visibility === 'collapse' || style.display === 'none'
      || style.opacity === '0' || hiddenByClosedDetails(element);
  };
  const visible = element => !!element && !cssHidden(element)
    && (element.getClientRects().length > 0 || getComputedStyle(element).display === 'contents');
  const omit = 'script,style,noscript,template,nav,footer,aside,button,input,textarea,select,[contenteditable=""],[contenteditable="true" i],[contenteditable="plaintext-only" i],[hidden],[aria-hidden="true"]';
  const normalize = value => String(value || '').replace(/\s+/g, ' ').trim();
  const normalizedLabel = value => normalize(value)
    .replace(/([\p{Ll}\p{Nd}])(\p{Lu})/gu, '$1 $2').replace(/(\p{Lu})(\p{Lu}\p{Ll})/gu, '$1 $2')
    .toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  const label = normalizedLabel(section);
  const matchesLabel = value => {
    const actual = normalizedLabel(value);
    return !!label && (actual === label || actual.endsWith(' ' + label));
  };
  const loadingText = value => {
    const actual = normalize(value).toLowerCase().replace(/[.\s…]+$/g, '');
    return ['loading', 'loading content', 'loading page', 'please wait'].includes(actual)
      || !!section && actual === 'loading ' + normalize(section).toLowerCase();
  };
  const placeholder = value => {
    const actual = normalize(value).toLowerCase().replace(/[.\s…]+$/g, '');
    return loadingText(value)
      || !!section && [ normalize(section).toLowerCase() + ' unavailable', 'no ' + normalize(section).toLowerCase() + ' available',
        'no ' + normalize(section).toLowerCase() + ' provided' ].includes(actual);
  };
  const titleChallenge = /^(?:sign[ -]?in|log[ -]?in|access denied|forbidden|just a moment|security check|captcha)(?:[.!:]|\s*[-|]\s*.+)?$/i;
  const challenge = /verify (?:you are|that you are) (?:a )?human|complete the (?:security|captcha) challenge|captcha verification|access denied|you (?:must|need to) (?:sign|log) in|enable javascript and cookies to continue|checking your browser|your request (?:has been|was) blocked/i;
  const result = (status, text = '', ready = true, detail = '') =>
    ({status, text, ready, detail: detail.slice(0, 1000), url: location.href, title: document.title.slice(0, 4096)});
  const localText = (element, maximum = 500) => {
    let text = '', count = 0;
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    while (count++ < 100 && text.length < maximum && walker.nextNode()) {
      const node = walker.currentNode;
      if (visible(node.parentElement) && !node.parentElement.closest(omit))
        text += ' ' + node.nodeValue.slice(0, maximum - text.length);
    }
    return normalize(text).slice(0, maximum);
  };
  // Collapsed disclosure widgets: a details element that is not open, or any aria-expanded="false"
  // toggle. Only hidden text that is not itself a control (links, options, menu items) counts as
  // unread content; a filter or menu dropdown is reported but does not downgrade the read.
  const control = 'a,label,option,[role="option"],[role="menuitem"],[role="menuitemcheckbox"],[role="menuitemradio"],[role="tab"],[role="button"],[role="link"],[role="switch"],[role="checkbox"],[role="radio"],[role="listbox"],[role="menu"],[role="menubar"],[role="tablist"],[role="radiogroup"],[role="toolbar"]';
  const visibleText = (element, maximum = 60) => {
    let text = '', count = 0;
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    while (count++ < 50 && text.length < maximum && walker.nextNode()) {
      const node = walker.currentNode;
      if (visible(node.parentElement)) text += ' ' + node.nodeValue.slice(0, maximum);
    }
    return normalize(text).slice(0, maximum);
  };
  const summaryOf = widget => widget.tagName === 'DETAILS'
    ? Array.from(widget.children).find(child => child.tagName === 'SUMMARY') : null;
  const widgetLabel = widget => {
    const ids = (widget.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean).slice(0, 3);
    const text = normalize(widget.getAttribute('aria-label') || '')
      || ids.map(id => { const element = document.getElementById(id); return element ? visibleText(element) : ''; }).join(' ').trim()
      || visibleText(summaryOf(widget) || widget)
      || normalize(widget.getAttribute('title') || '')
      || widget.tagName.toLowerCase();
    return text.slice(0, 40);
  };
  const hiddenText = widget => {
    let targets;
    if (widget.tagName === 'DETAILS') targets = Array.from(widget.children).filter(child => child.tagName !== 'SUMMARY');
    else {
      const ids = (widget.getAttribute('aria-controls') || '').split(/\s+/).filter(Boolean).slice(0, 5);
      targets = ids.map(id => document.getElementById(id)).filter(Boolean);
      if (!targets.length) targets = [widget];
    }
    let text = '', count = 0;
    for (const target of targets) {
      const walker = document.createTreeWalker(target, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
        acceptNode(node) {
          if (node.nodeType !== Node.ELEMENT_NODE) return NodeFilter.FILTER_ACCEPT;
          return node.matches(omit) || node.matches(control) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
        }
      });
      while (count++ < 200 && text.length < 200 && walker.nextNode()) {
        const node = walker.currentNode;
        if (node.nodeType === Node.TEXT_NODE && !visible(node.parentElement)) text += normalize(node.nodeValue) + ' ';
      }
    }
    return text.trim();
  };
  const collapsedWidgets = root => {
    const unread = [], controls = [];
    for (const widget of Array.from(root.querySelectorAll('details:not([open]),[aria-expanded="false"]')).slice(0, 50)) {
      if (!visible(widget) || widget.parentElement && widget.parentElement.closest(omit)) continue;
      (hiddenText(widget) ? unread : controls).push(widgetLabel(widget));
    }
    return {unread, controls};
  };
  const describe = (labels, noun) => {
    const shown = labels.filter(Boolean).slice(0, 5).map(label => '"' + label + '"');
    return labels.length + ' ' + noun + (labels.length === 1 ? '' : 's')
      + (shown.length ? ' (' + shown.join(', ') + (labels.length > shown.length ? ', …' : '') + ')' : '');
  };
  const access = Array.from(document.querySelectorAll('input[type="password"],iframe[src*="recaptcha"],iframe[src*="captcha"],#challenge-form,.g-recaptcha'))
    .slice(0, 50).some(visible);
  if (titleChallenge.test(document.title)) return result('blocked');
  if (!document.body) return result('missing', '', false);
  const candidates = [], headings = [];
  let traversed = 0, pageSample = '', searchLimited = false, headingChallenge = false, pendingContent = false;
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      return node.nodeType === Node.ELEMENT_NODE && (node.matches(omit) || cssHidden(node))
        ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
    }
  });
  while (walker.nextNode()) {
    if (++traversed > 20000) { searchLimited = true; break; }
    const node = walker.currentNode;
    if (node.nodeType === Node.TEXT_NODE) {
      if (visible(node.parentElement) && loadingText(node.nodeValue)) pendingContent = true;
      if (pageSample.length < 12000 && visible(node.parentElement))
        pageSample += ' ' + node.nodeValue.slice(0, 12000 - pageSample.length);
      continue;
    }
    if (!visible(node)) continue;
    if (node.getAttribute('aria-busy') === 'true' || node.getAttribute('role') === 'progressbar') pendingContent = true;
    const isHeading = /^H[1-6]$/.test(node.tagName) || node.getAttribute('role') === 'heading';
    if ((node.tagName === 'H1' || node.getAttribute('role') === 'heading' && node.getAttribute('aria-level') === '1')
        && titleChallenge.test(localText(node))) headingChallenge = true;
    if (!section) continue;
    if (isHeading && headings.length < 100 && matchesLabel(localText(node))) headings.push(node);
    if (candidates.length >= 30) { searchLimited = true; continue; }
    const identified = matchesLabel(node.id) || matchesLabel(node.getAttribute('data-testid') || '') || matchesLabel(node.tagName);
    const labelIds = (node.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean).slice(0, 5);
    const labelled = matchesLabel(node.getAttribute('aria-label') || '') || labelIds.some(id => {
      const heading = document.getElementById(id);
      return heading && matchesLabel(localText(heading));
    });
    if (!isHeading && (identified || labelled)) candidates.push({roots: [node], partial: false});
  }
  for (const heading of headings) {
    if (candidates.some(candidate => candidate.roots.some(root => root.contains(heading)))) continue;
    const level = Number(heading.tagName.slice(1)) || Number(heading.getAttribute('aria-level')) || 2;
    let cursor = heading, roots = [];
    for (let climb = 0; climb < 3 && cursor && cursor !== document.body && roots.length === 0;
         climb++, cursor = cursor.parentElement) {
      let sibling = cursor.nextElementSibling, count = 0;
      for (; sibling && count++ < 30; sibling = sibling.nextElementSibling) {
        const nextHeading = /^H[1-6]$/.test(sibling.tagName) ? sibling : sibling.querySelector('h1,h2,h3,h4,h5,h6,[role="heading"]');
        const nextLevel = nextHeading ? Number(nextHeading.tagName.slice(1)) || Number(nextHeading.getAttribute('aria-level')) || 2 : 7;
        if (nextHeading && nextLevel <= level) break;
        if (!sibling.matches(omit) && visible(sibling)) roots.push(sibling);
      }
      if (sibling && count > 30) searchLimited = true;
    }
    if (roots.length) candidates.push({roots, partial: true});
    if (candidates.length >= 30) { searchLimited = true; break; }
  }
  if (!section) {
    const eligible = selector => Array.from(document.querySelectorAll(selector))
      .filter(node => {
        if (!visible(node) || node.closest(omit)) return false;
        for (let parent = node.parentElement; parent; parent = parent.parentElement)
          if (cssHidden(parent)) return false;
        return true;
      });
    let roots = eligible('main,[role="main"]');
    if (!roots.length) roots = eligible('article');
    if (!roots.length && visible(document.body)) roots = [document.body];
    roots = roots.filter(root => !roots.some(other => other !== root && other.contains(root)));
    if (roots.length > 30) searchLimited = true;
    if (roots.length) candidates.push({roots: roots.slice(0, 30), partial: false});
  }
  const read = candidate => {
    let text = '', count = 0, truncated = false, partial = candidate.partial, loading = false, embedded = 0;
    const unread = [], controls = [];
    const append = piece => {
      if (text.length + piece.length > maximumCharacters) truncated = true;
      let end = Math.min(piece.length, Math.max(0, maximumCharacters - text.length));
      if (end > 0 && end < piece.length && /[\uD800-\uDBFF]/.test(piece[end - 1])
          && /[\uDC00-\uDFFF]/.test(piece[end])) end--;
      text += piece.slice(0, end);
    };
    for (const root of candidate.roots) {
      if (root.closest('[aria-busy="true"]') || Array.from(root.querySelectorAll('[aria-busy="true"],[role="progressbar"]')).some(visible)) loading = true;
      embedded += Array.from(root.querySelectorAll('iframe,frame,object,embed,canvas')).slice(0, 50).filter(visible).length;
      const collapsed = collapsedWidgets(root);
      unread.push(...collapsed.unread);
      controls.push(...collapsed.controls);
      const children = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
        acceptNode(node) {
          if (node.nodeType !== Node.ELEMENT_NODE) return NodeFilter.FILTER_ACCEPT;
          if (cssHidden(node)) return NodeFilter.FILTER_REJECT;
          return node.matches(omit)
            || ((/^H[1-6]$/.test(node.tagName) || node.getAttribute('role') === 'heading') && matchesLabel(localText(node)))
            ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
        }
      });
      while (children.nextNode()) {
        if (++count > 20000) { truncated = true; break; }
        const node = children.currentNode;
        if (node.nodeType === Node.TEXT_NODE && visible(node.parentElement)) {
          if (placeholder(node.nodeValue)) {
            if (loadingText(node.nodeValue)) loading = true;
          }
          else append(normalize(node.nodeValue) + ' ');
        }
        else if (node.nodeType === Node.ELEMENT_NODE) {
          if (/^(P|DIV|SECTION|ARTICLE|BR|LI|TR|H[1-6])$/.test(node.tagName)) append('\n');
          if (/^(TD|TH)$/.test(node.tagName)) append(' | ');
        }
        if (truncated) break;
      }
      if (truncated) break;
      if (text.length < maximumCharacters) append('\n');
    }
    text = text.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
    const meaningful = text.trim();
    const gate = /^(?:(?:(?:you(?: must| need to| are required to)|please)\s+)?(?:sign[ -]?in|log[ -]?in)\s+(?:to|in order to)\s+(?:view|read|access|continue)\b[\s\S]{0,300}|(?:access denied|forbidden)[.!:]?(?:\s+(?:please|you must|you need to|sign in|log in)\b[\s\S]{0,200})?|(?:please\s+)?(?:complete|solve) the (?:captcha|security) challenge[\s\S]{0,200})[.!]?$/i;
    if (gate.test(meaningful) || titleChallenge.test(meaningful)) return result('blocked');
    if (!meaningful || placeholder(meaningful))
      return result('missing', '', document.readyState === 'complete' && !loading);
    if (unread.length || embedded) partial = true;
    const notes = [];
    if (unread.length) notes.push(describe(unread, 'collapsed section') + ' hold unread text and were not expanded.');
    if (controls.length) notes.push(describe(controls, 'collapsed control') + ' such as menus or filters hold no content text and were left closed.');
    if (embedded) notes.push(embedded + ' embedded frame, object or canvas element' + (embedded === 1 ? ' is' : 's are') + ' not readable as text.');
    if (loading) notes.push('A loading indicator was present.');
    else if (unread.length || controls.length || embedded) notes.push('No loading indicator was present.');
    if (candidate.partial) notes.push('The section boundary was inferred from a heading, so neighbouring content may be included or missing.');
    return result(truncated || searchLimited ? 'truncated' : partial || loading ? 'partial' : 'ok', text, !loading, notes.join(' '));
  };
  // Keep the enclosing identified region so nested matching IDs cannot hide
  // sibling paragraphs or cause duplicated source text.
  const unique = candidates.filter(candidate => !candidates.some(other => other !== candidate
    && other.roots.length === 1 && candidate.roots.length === 1 && !other.partial
    && candidate.roots[0] !== other.roots[0] && other.roots[0].contains(candidate.roots[0])));
  const extracted = unique.map(read);
  if (extracted.some(value => value.status === 'blocked')) return result('blocked');
  const found = extracted.filter(value => value.text);
  if (found.length) {
    const selected = found[0];
    if (found.length > 1 && selected.status === 'ok') {
      selected.status = 'partial';
      selected.detail = (found.length + ' regions matched the requested section; only the first was read. ' + selected.detail).trim().slice(0, 1000);
    }
    return selected;
  }
  // Only use whole-page challenge text when no actual content region was found.
  if (access || headingChallenge || challenge.test(normalize(pageSample))) return result('blocked');
  return result('missing', '', document.readyState === 'complete' && !pendingContent
    && extracted.every(value => value.ready));
}
