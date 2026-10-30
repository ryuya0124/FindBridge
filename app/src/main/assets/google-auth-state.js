(() => {
    'use strict';
    if (location.origin !== 'https://accounts.google.com' || window.top !== window) return;
    let sent = false, scheduled = false;
    const visible = e => e && e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden';
    const check = () => {
        scheduled = false;
        if (sent || location.pathname === '/MergeSession') return;
        const input = [...document.querySelectorAll('input:not([type=hidden])')].some(visible);
        const chooser = [...document.querySelectorAll('[data-identifier]')].some(visible);
        if (input || chooser || /\/challenge\//.test(location.pathname)) {
            sent = true;
            try { FindBridgeGoogleAuthState.postMessage('verification'); } catch (_) {}
        }
    };
    const changed = () => { if (!scheduled) { scheduled = true; queueMicrotask(check); } };
    new MutationObserver(changed).observe(document, {subtree:true, childList:true, attributes:true});
    for (const event of ['DOMContentLoaded', 'load', 'pageshow']) addEventListener(event, changed);
    changed();
})();
