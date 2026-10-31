(() => {
  "use strict";
  const allowed = (origin) =>
    /^https:\/\/(?:www\.icloud\.com(?:\.cn)?|(?:idmsa|account)\.apple\.com)$/.test(
      origin || "",
    );
  if (!allowed(location.origin)) return;
  const routes = new Map();
  const children = new Map();
  let epoch = 0;
  const broadcast = (type, value = epoch) => {
    epoch = Math.max(epoch, Number(value) || 0);
    dispatchEvent(new CustomEvent(type, { detail: { epoch } }));
    for (const frame of document.querySelectorAll("iframe")) {
      let origin = children.get(frame.contentWindow);
      if (!origin) {
        try {
          origin = new URL(frame.src).origin;
        } catch (_) {}
      }
      if (allowed(origin))
        frame.contentWindow.postMessage({ type, epoch }, origin);
    }
  };
  window.FindBridgeLoginRelay = {
    resume: (value) => broadcast("findbridge.auth.resume", value),
    suspend: () => broadcast("findbridge.auth.suspend"),
    cancel: () => broadcast("findbridge.auth.cancel"),
  };
  let layoutPending = false;
  const changedLayout = () => {
    if (layoutPending) return;
    layoutPending = true;
    requestAnimationFrame(() => {
      layoutPending = false;
      broadcast("findbridge.auth.resume");
    });
  };
  new MutationObserver((records) => {
    const frames = [...document.querySelectorAll("iframe")];
    if (
      records.some((record) =>
        frames.some(
          (frame) => record.target === frame || record.target.contains?.(frame),
        ),
      )
    )
      changedLayout();
  }).observe(document, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: ["style", "class", "hidden"],
  });
  addEventListener("resize", changedLayout);
  addEventListener("scroll", changedLayout, true);
  const result = (data) => {
    const route = routes.get(data.id);
    if (route) {
      routes.delete(data.id);
      route.source.postMessage(data, route.origin);
    } else
      dispatchEvent(
        new CustomEvent("findbridge.auth.result", { detail: data }),
      );
  };
  if (window.top === window && typeof FindBridgeLoginTap !== "undefined") {
    FindBridgeLoginTap.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === "findbridge.auth.result") result(data);
      } catch (_) {}
    };
  }
  addEventListener("message", (event) => {
    const data = event.data;
    if (
      [
        "findbridge.auth.resume",
        "findbridge.auth.suspend",
        "findbridge.auth.cancel",
      ].includes(data?.type)
    ) {
      if (
        event.source === parent &&
        allowed(event.origin) &&
        window.top !== window
      )
        broadcast(data.type, data.epoch);
      return;
    }
    if (data?.type === "findbridge.auth.result") {
      if (
        event.source === parent &&
        allowed(event.origin) &&
        window.top !== window
      )
        result(data);
      return;
    }
    if (!/^https:\/\/(idmsa|account)\.apple\.com$/.test(event.origin)) return;
    if (
      data?.type !== "findbridge.auth.submit" ||
      !["account", "password"].includes(data.stage) ||
      !/^[a-f0-9]{32}$/.test(data.id || "")
    )
      return;
    const frame = [...document.querySelectorAll("iframe")].find(
      (f) => f.contentWindow === event.source,
    );
    if (!frame || data.mode !== "activate") return;
    const r = frame.getBoundingClientRect();
    if (children.size >= 16) children.delete(children.keys().next().value);
    children.set(event.source, event.origin);
    if (
      r.width <= 0 ||
      r.height <= 0 ||
      (frame.checkVisibility &&
        !frame.checkVisibility({
          visibilityProperty: true,
          opacityProperty: true,
        }))
    ) {
      event.source.postMessage(
        {
          type: "findbridge.auth.result",
          id: data.id,
          stage: data.stage,
          status: "waiting",
          reason: "viewport",
          epoch,
        },
        event.origin,
      );
      return;
    }
    const request = {
      type: data.type,
      id: data.id,
      stage: data.stage,
      mode: "activate",
    };
    // Bound entries also when a document navigates before its acknowledgement returns.
    if (routes.size >= 16) routes.delete(routes.keys().next().value);
    routes.set(data.id, { source: event.source, origin: event.origin });
    if (window.top === window)
      FindBridgeLoginTap.postMessage(JSON.stringify(request));
    else {
      const parentOrigin = location.ancestorOrigins?.[0];
      if (allowed(parentOrigin)) parent.postMessage(request, parentOrigin);
    }
  });
})();
