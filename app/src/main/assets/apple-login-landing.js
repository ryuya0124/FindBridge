(() => {
    "use strict";
    if (!/^https:\/\/www\.icloud\.com(?:\.cn)?$/.test(location.origin)) return;
    if (window.top === window) addEventListener("message", event => {
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
        FindBridgeLoginTap.postMessage(JSON.stringify(point));
    });
    let done = false;
    const labels = /^(sign in|サインイン|ログイン|登录|登入|로그인|iniciar sesión|se connecter|anmelden)$/iu;
    const timer = setInterval(() => {
        if (done) return;
        const button = [...document.querySelectorAll("button, [role=button]")].find(element =>
            element.getClientRects().length > 0 && labels.test(element.textContent.trim()));
        if (button) { done = true; button.click(); clearInterval(timer); }
    }, 350);
    setTimeout(() => clearInterval(timer), 120000);
})();
