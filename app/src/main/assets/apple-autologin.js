(() => {
    "use strict";
    if (!/^https:\/\/(idmsa|account)\.apple\.com$/.test(location.origin)) return;
    if (!/^\/(appleauth|auth)(\/|$)|^\/signin(?:\/|$)/.test(location.pathname)) return;
    const login = __FINDBRIDGE_LOGIN__;
    let accountSubmitted = false;
    let accountSubmittedAt = 0;
    let passwordSubmitted = false;
    let stopped = false;
    let lastFilledAt = 0;
    const visible = element => {
        if (!element || !element.getClientRects().length) return false;
        if (element.checkVisibility) return element.checkVisibility({opacityProperty: true, visibilityProperty: true, checkOpacity: true, checkVisibilityCSS: true});
        for (let current = element; current; current = current.parentElement) {
            const style = getComputedStyle(current);
            if (style.visibility !== "visible" || Number(style.opacity) === 0) return false;
        }
        return true;
    };
    const notify = state => { try { FindBridgeLoginState.postMessage(state); } catch (_) {} };
    notify("ready");
    const fill = (field, value) => {
        if (!field || field.value === value) return;
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(field, value);
        field.dispatchEvent(new Event("input", {bubbles: true}));
        field.dispatchEvent(new Event("change", {bubbles: true}));
        lastFilledAt = performance.now();
        notify(field.type === "password" ? "password-filled" : "account-filled");
    };
    const nextLabels = /^(sign\s*in|continue|next|サインイン|ログイン|続ける|次へ|登录|登入|继续|繼續|下一步|로그인|계속|다음|iniciar sesión|continuar|siguiente|se connecter|continuer|suivant|anmelden|weiter)$/iu;
    const nextButton = () => {
        const candidates = [...document.querySelectorAll("#sign-in, #continue, button, input[type=submit], [role=button]")];
        return candidates.find(button => {
            if (!visible(button)) return false;
            const labels = [button.getAttribute("aria-label"), button.textContent, button.value]
                .filter(Boolean).map(label => label.trim().replace(/\s+/gu, " "));
            return button.id === "sign-in" || labels.some(label => nextLabels.test(label));
        });
    };
    const submit = stage => {
        // Let Apple update its controlled inputs and finish installing the sign-in handler.
        if (performance.now() - lastFilledAt < 1500) return false;
        const button = nextButton();
        if (!visible(button) || button.disabled || button.getAttribute("aria-disabled") === "true") return false;
        button.scrollIntoView({behavior: "instant", block: "nearest"});
        const r = button.getBoundingClientRect();
        const request = {type: "findbridge.auth.submit", stage, x: r.x + r.width / 2,
            y: r.y + r.height / 2, width: innerWidth, height: innerHeight};
        if (window.top === window) {
            const viewport = window.visualViewport;
            request.x -= viewport?.offsetLeft || 0;
            request.y -= viewport?.offsetTop || 0;
            request.width = viewport?.width || innerWidth;
            request.height = viewport?.height || innerHeight;
            FindBridgeLoginTap.postMessage(JSON.stringify(request));
        } else {
            const parentOrigin = location.ancestorOrigins?.[0];
            if (!/^https:\/\/(?:www\.icloud\.com(?:\.cn)?|(?:idmsa|account)\.apple\.com)$/.test(parentOrigin || "")) return false;
            parent.postMessage(request, parentOrigin);
        }
        return true;
    };
    const tick = () => {
        if (stopped) return;
        if (visible(document.querySelector("#char0, #security-code, input[autocomplete=one-time-code]"))) {
            stopped = true; notify("verification"); return;
        }
        if (passwordSubmitted && [...document.querySelectorAll(".si-error, #errMsg, .error-message")].some(visible)) {
            stopped = true; notify("rejected"); return;
        }
        const account = document.querySelector("#account_name_text_field, input[autocomplete=username], input[type=email]");
        const password = document.querySelector("#password_text_field, input[autocomplete=current-password]");
        // Some Apple variants navigate directly to a password-only document.
        if (!accountSubmitted && visible(password) && (!visible(account) || account.readOnly || account.disabled)) {
            accountSubmitted = true;
            accountSubmittedAt = performance.now() - 1500;
        }
        if (visible(account) && !accountSubmitted) {
            fill(account, login.account);
            accountSubmitted = submit("account");
            if (accountSubmitted) accountSubmittedAt = performance.now();
        } else if (accountSubmitted && performance.now() - accountSubmittedAt >= 1500 && visible(password) && !passwordSubmitted) {
            fill(password, login.password);
            passwordSubmitted = submit("password");
        }
    };
    const timer = setInterval(tick, 350);
    setTimeout(() => { stopped = true; clearInterval(timer); }, 120000);
})();
