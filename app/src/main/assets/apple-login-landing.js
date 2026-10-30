(() => {
    "use strict";
    if (!/^https:\/\/www\.icloud\.com(?:\.cn)?$/.test(location.origin)) return;
    let done = false, scheduled = false;
    const labels = /^(sign in|サインイン|ログイン|登录|登入|로그인|iniciar sesión|se connecter|anmelden)$/iu;
    const schedule = () => {
        if (done || scheduled) return;
        scheduled = true;
        requestAnimationFrame(() => {
            scheduled = false;
            const button = [...document.querySelectorAll("button, [role=button]")].find(element =>
                element.getClientRects().length && !element.disabled && element.getAttribute("aria-disabled") !== "true" && labels.test(element.textContent.trim()));
            if (button) { done = true; observer.disconnect(); button.click(); }
        });
    };
    const observer = new MutationObserver(schedule);
    observer.observe(document, {subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ["disabled", "class", "style", "hidden", "aria-disabled"]});
    for (const name of ["DOMContentLoaded", "load", "pageshow", "transitionend", "animationend"]) addEventListener(name, schedule, true);
    schedule();
})();
