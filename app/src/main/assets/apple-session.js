// Observes read-only Find Devices responses in Apple's own origin.
// Account identifiers, passwords and verification codes are never inspected.
(() => {
  // Find Devices can run in a trusted iCloud iframe. Each document observes
  // its own requests; native code validates both its origin and the top page.
  if (window.__findBridgeInstalled) return;
  window.__findBridgeInstalled = true;
  const readEndpoint = (value) => {
    try {
      const url = new URL(value, location.href);
      return (
        url.protocol === "https:" &&
        /^p\d+-fmipweb\.icloud\.com(?:\.cn)?$/.test(url.hostname) &&
        /^\/fmipservice\/client\/web\/(initClient|refreshClient)$/.test(
          url.pathname,
        )
      );
    } catch {
      return false;
    }
  };
  const report = (url, body, response) => {
    try {
      const parsed =
        typeof response === "string" ? JSON.parse(response) : response;
      if (
        readEndpoint(url) &&
        [401, 421, 450].includes(Number(parsed?.statusCode))
      ) {
        failed(url, Number(parsed.statusCode));
        return;
      }
      if (!readEndpoint(url)) return;
      if (!Array.isArray(parsed?.content)) {
        failed(url, 0);
        return;
      }
      FindBridgeData.postMessage(
        JSON.stringify({
          url,
          body: typeof body === "string" ? body : "{}",
          response: parsed,
        }),
      );
    } catch {
      failed(url, 0);
    }
  };
  const failed = (url, status) => {
    if (readEndpoint(url) && (status === 0 || status >= 400)) {
      try {
        FindBridgeSessionState.postMessage(
          [401, 421, 450].includes(status)
            ? "authenticationRequired"
            : "serviceUnavailable",
        );
      } catch (_) {
        // Only the native Apple-origin observer receives this event.
      }
    }
  };
  const originalFetch = window.fetch;
  window.fetch = function (input, options) {
    let url, body;
    try {
      // fetch accepts strings, URL objects and Request objects. Inspecting
      // one must never delay or replace the promise returned to Apple's app.
      url = new URL(
        input instanceof Request ? input.url : String(input),
        location.href,
      ).href;
      if (readEndpoint(url)) {
        body =
          options?.body ??
          (input instanceof Request ? input.clone().text() : "{}");
      }
    } catch (_) {}
    const pending = originalFetch.apply(this, arguments);
    if (readEndpoint(url)) {
      pending.then(
        (response) => {
          failed(url, response.status);
          if (response.ok) {
            Promise.all([body, response.clone().text()])
              .then(([requestBody, text]) => report(url, requestBody, text))
              .catch(() => failed(url, 0));
          }
        },
        () => failed(url, 0),
      );
    }
    return pending;
  };
  const originalOpen = XMLHttpRequest.prototype.open;
  const originalSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__findBridgeUrl = new URL(url, location.href).href;
    return originalOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    if (readEndpoint(this.__findBridgeUrl)) {
      for (const event of ["error", "timeout"]) {
        this.addEventListener(event, () => failed(this.__findBridgeUrl, 0), {
          once: true,
        });
      }
      this.addEventListener(
        "load",
        () => {
          failed(this.__findBridgeUrl, this.status);
          if (this.status === 200) {
            report(
              this.__findBridgeUrl,
              body,
              this.responseType === "json" ? this.response : this.responseText,
            );
          }
        },
        { once: true },
      );
    }
    return originalSend.apply(this, arguments);
  };
})();
