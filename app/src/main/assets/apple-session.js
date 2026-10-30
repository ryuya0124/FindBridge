// Observes read-only Find Devices responses in Apple's own origin.
// Account identifiers, passwords and verification codes are never inspected.
(() => {
  if (window.top !== window) return;
  if (window.__findBridgeInstalled) return;
  window.__findBridgeInstalled = true;
  const readEndpoint = (value) => {
    try {
      const url = new URL(value, location.href);
      return (
        url.protocol === 'https:' &&
        /^p\d+-fmipweb\.icloud\.com(?:\.cn)?$/.test(url.hostname) &&
        /^\/fmipservice\/client\/web\/(initClient|refreshClient)$/.test(url.pathname)
      );
    } catch {
      return false;
    }
  };
  const report = (url, body, response) => {
    try {
      const parsed = typeof response === 'string' ? JSON.parse(response) : response;
      if (
        readEndpoint(url) &&
        [401, 421, 450].includes(Number(parsed?.statusCode))
      ) {
        failed(url, Number(parsed.statusCode));
        return;
      }
      if (!readEndpoint(url) || !Array.isArray(parsed?.content)) return;
      FindBridgeData.postMessage(
        JSON.stringify({
          url,
          body: typeof body === 'string' ? body : '{}',
          response: parsed,
        }),
      );
    } catch {
      // Ignore unrelated or incomplete responses.
    }
  };
  const failed = (url, status) => {
    if (readEndpoint(url) && [401, 421, 450].includes(status)) {
      try {
        FindBridgeSessionState.postMessage('authenticationRequired');
      } catch (_) {
        // Only the native Apple-origin observer receives this event.
      }
    }
  };
  const originalFetch = window.fetch;
  window.fetch = async function (input, options) {
    const url =
      typeof input === 'string' ? new URL(input, location.href).href : input.url;
    const body =
      options?.body ??
      (input instanceof Request ? await input.clone().text() : '{}');
    const response = await originalFetch.apply(this, arguments);
    failed(url, response.status);
    if (readEndpoint(url) && response.ok) {
      response.clone().text().then((text) => report(url, body, text));
    }
    return response;
  };
  const originalOpen = XMLHttpRequest.prototype.open;
  const originalSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__findBridgeUrl = new URL(url, location.href).href;
    return originalOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    if (readEndpoint(this.__findBridgeUrl)) {
      this.addEventListener(
        'load',
        () => {
          failed(this.__findBridgeUrl, this.status);
          if (this.status === 200) {
            report(
              this.__findBridgeUrl,
              body,
              this.responseType === 'json' ? this.response : this.responseText,
            );
          }
        },
        { once: true },
      );
    }
    return originalSend.apply(this, arguments);
  };
})();
