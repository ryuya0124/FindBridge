(() => {
    "use strict";
    if (!/^https:\/\/(idmsa|account)\.apple\.com$/.test(location.origin)) return;
    if (!/^\/(appleauth|auth)(\/|$)|^\/signin(?:\/|$)/.test(location.pathname)) return;
    const login = __FINDBRIDGE_LOGIN__;
    let accountSubmitted = false, passwordSubmitted = false, stopped = false;
    let pending = null, waiting = false, scheduled = false, settling = false;
    let resumeEpoch = 0, activeSince = 0, remaining = 120000, watchdog = null;
    const notify = state => { try { FindBridgeLoginState.postMessage(state); } catch (_) {} };
    const deadline = active => {
        if (activeSince) remaining -= performance.now() - activeSince;
        activeSince = 0;
        clearTimeout(watchdog);
        if (active && !stopped && document.visibilityState !== "hidden") {
            activeSince = performance.now();
            // Failure watchdog only; it never delays normal authentication.
            watchdog = setTimeout(() => { stopped = true; observer.disconnect(); notify("timeout"); }, Math.max(1, remaining));
        }
    };
    const visible = element => {
        if (!element || !element.getClientRects().length) return false;
        if (element.checkVisibility) return element.checkVisibility({opacityProperty: true, visibilityProperty: true, checkOpacity: true, checkVisibilityCSS: true});
        for (let current = element; current; current = current.parentElement) {
            const style = getComputedStyle(current);
            if (style.visibility !== "visible" || Number(style.opacity) === 0) return false;
        }
        return true;
    };
    const schedule = () => {
        if (scheduled || stopped || document.visibilityState === "hidden") return;
        scheduled = true;
        requestAnimationFrame(() => { scheduled = false; tick(); });
    };
    const finish = state => {
        stopped = true; deadline(false); observer.disconnect(); notify(state);
    };
    const fill = (field, value) => {
        if (!field || field.value === value) return false;
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(field, value);
        field.dispatchEvent(new Event("input", {bubbles: true}));
        field.dispatchEvent(new Event("change", {bubbles: true}));
        settling = true;
        // Let the input's controlled state commit at rendering boundaries, without a timer.
        requestAnimationFrame(() => requestAnimationFrame(() => { settling = false; schedule(); }));
        return true;
    };
    const nextLabels = /^(sign\s*in|continue|next|サインイン|ログイン|続ける|次へ|登录|登入|继续|繼續|下一步|로그인|계속|다음|iniciar sesión|continuar|siguiente|se connecter|continuer|suivant|anmelden|weiter)$/iu;
    const nextButton = () => [...document.querySelectorAll("#sign-in, #continue, button, input[type=submit], [role=button]")].find(button => {
        if (!visible(button)) return false;
        const labels = [button.getAttribute("aria-label"), button.textContent, button.value]
            .filter(Boolean).map(label => label.trim().replace(/\s+/gu, " "));
        return button.id === "sign-in" || labels.some(label => nextLabels.test(label));
    });
    const submit = stage => {
        if (pending || waiting || settling) return;
        const button = nextButton();
        if (!button || button.disabled || button.getAttribute("aria-disabled") === "true") return;
        button.scrollIntoView({behavior: "instant", block: "nearest"});
        // Native focus acquisition may leave this document's activeElement unchanged
        // while Chromium's focused frame is its parent. Re-establish both explicitly.
        window.focus();
        button.blur();
        button.focus();
        if (document.activeElement !== button) return;
        const id = [...crypto.getRandomValues(new Uint32Array(4))].map(n => n.toString(16).padStart(8, "0")).join("");
        const request = {type: "findbridge.auth.submit", id, stage, mode: "activate"};
        if (window.top === window) {
            pending = {id, stage}; FindBridgeLoginTap.postMessage(JSON.stringify(request));
        } else {
            const parentOrigin = location.ancestorOrigins?.[0];
            if (!/^https:\/\/(?:www\.icloud\.com(?:\.cn)?|(?:idmsa|account)\.apple\.com)$/.test(parentOrigin || "")) return;
            pending = {id, stage}; parent.postMessage(request, parentOrigin);
        }
    };
    const tick = () => {
        if (stopped || settling || waiting || document.visibilityState === "hidden") return;
        if (visible(document.querySelector("#char0, #security-code, input[autocomplete=one-time-code]"))) { finish("verification"); return; }
        if (passwordSubmitted && [...document.querySelectorAll(".si-error, #errMsg, .error-message")].some(visible)) { finish("rejected"); return; }
        const account = document.querySelector("#account_name_text_field, input[autocomplete=username], input[type=email]");
        const password = document.querySelector("#password_text_field, input[autocomplete=current-password]");
        if (!accountSubmitted && visible(password) && (!visible(account) || account.readOnly || account.disabled)) accountSubmitted = true;
        if (visible(account) && !accountSubmitted) {
            if (!fill(account, login.account)) submit("account");
        } else if (accountSubmitted && visible(password) && !passwordSubmitted) {
            if (!fill(password, login.password)) submit("password");
        }
    };
    addEventListener("findbridge.auth.result", event => {
        const result = event.detail;
        if (!pending || result?.id !== pending.id || result.stage !== pending.stage) return;
        const stage = pending.stage;
        pending = null;
        if (result.status === "dispatched") {
            if (stage === "account") accountSubmitted = true; else passwordSubmitted = true;
        }
        waiting = result.status === "waiting" && !(resumeEpoch > Number(result.epoch || 0));
        deadline(!(waiting && result.reason === "focus"));
        if (!waiting) schedule();
    });
    addEventListener("findbridge.auth.resume", event => {
        resumeEpoch = Math.max(resumeEpoch, Number(event.detail?.epoch || 0));
        waiting = false; deadline(true); schedule();
    });
    addEventListener("findbridge.auth.suspend", () => { waiting = true; deadline(false); });
    addEventListener("visibilitychange", () => {
        if (document.visibilityState === "hidden") deadline(false);
        else { waiting = false; deadline(true); schedule(); }
    });
    for (const name of ["DOMContentLoaded", "load", "pageshow", "resize", "scroll", "transitionend", "animationend", "input", "change"])
        addEventListener(name, schedule, true);
    const observer = new MutationObserver(schedule);
    observer.observe(document, {subtree: true, childList: true, characterData: true, attributes: true,
        attributeFilter: ["class", "style", "hidden", "disabled", "readonly", "aria-disabled", "aria-busy", "type"]});
    notify("ready"); deadline(true); schedule();
})();
