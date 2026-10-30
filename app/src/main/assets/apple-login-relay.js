(() => {
    "use strict";
    if (!/^https:\/\/(?:www\.icloud\.com(?:\.cn)?|(?:idmsa|account)\.apple\.com)$/.test(location.origin)) return;
    addEventListener("message", event => {
        if (!/^https:\/\/(idmsa|account)\.apple\.com$/.test(event.origin)) return;
        const data = event.data;
        if (data?.type !== "findbridge.auth.submit" || !["account", "password"].includes(data.stage)) return;
        const frame = [...document.querySelectorAll("iframe")].find(f => f.contentWindow === event.source);
        if (!frame || ![data.x, data.y, data.width, data.height].every(Number.isFinite) || data.width <= 0 || data.height <= 0) return;
        const r = frame.getBoundingClientRect();
        const viewport = window.visualViewport;
        const point = {stage: data.stage, x: r.x + data.x * r.width / data.width - (viewport?.offsetLeft || 0),
            y: r.y + data.y * r.height / data.height - (viewport?.offsetTop || 0),
            width: viewport?.width || innerWidth, height: viewport?.height || innerHeight};
        if (point.x < 0 || point.y < 0 || point.x > point.width || point.y > point.height) return;
        if (window.top === window) FindBridgeLoginTap.postMessage(JSON.stringify(point));
        else {
            const parentOrigin = location.ancestorOrigins?.[0];
            if (/^https:\/\/(?:www\.icloud\.com(?:\.cn)?|(?:idmsa|account)\.apple\.com)$/.test(parentOrigin || ""))
                parent.postMessage({...point, type: "findbridge.auth.submit"}, parentOrigin);
        }
    });
})();
