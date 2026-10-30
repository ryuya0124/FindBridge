(() => {
    "use strict";
    if (!/^https:\/\/www\.icloud\.com(?:\.cn)?$/.test(location.origin)) return;
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
