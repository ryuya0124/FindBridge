(() => {
    "use strict";
    if (location.origin !== "https://www.google.com" || !/^\/android\/find(?:\/u\/[0-9]{1,3})?\/?$/.test(location.pathname)) return;
    if (window.top !== window || window.FindBridgeGoogleReader) return;
    const records = new Map();
    let queue = [], current = null, serial = "", running = false, scheduled = false;
    let deviceWatchdog = null, cycleWatchdog = null, expiredDevice = false;
    let waitingForCatalog = false, requestedIndex = __FINDBRIDGE_GOOGLE_INITIAL_INDEX__;
    let automating = false, lastDialog = false, backRequested = false;
    const visible = e => e && e.getClientRects().length && getComputedStyle(e).visibility !== "hidden";
    const dialog = () => [...document.querySelectorAll('[role="dialog"],[role="alertdialog"],[aria-modal="true"],dialog[open]')].find(visible);
    const state = () => { try { FindBridgeGoogleState.postMessage(dialog() ? "verification" : "loading"); } catch (_) {} };
    // The official verification/consent dialog remains interactive. Find Hub's remote
    // controls never receive owner input, including while the native cover changes state.
    for (const type of ['click', 'pointerdown', 'pointerup', 'touchstart', 'touchend', 'keydown'])
        addEventListener(type, event => {
            if (automating) return;
            const prompt = dialog();
            if (!prompt || !prompt.contains(event.target)) { event.preventDefault(); event.stopImmediatePropagation(); }
        }, {capture: true, passive: false});
    const click = element => { automating = true; try { element.click(); } finally { automating = false; } };
    const list = () => [...document.querySelectorAll('[data-index]')].flatMap(container => {
        if (!visible(container) || !/^\d+$/.test(container.dataset.index)) return [];
        // Mobile Find Hub puts data-index on the row around its clickable device card.
        const element = container.matches('button,[role="button"]') ? container :
            [...container.querySelectorAll('button,[role="button"]')].find(e => visible(e) && e.querySelector('img'));
        return element && visible(element) && element.querySelector('img') ? [{element,index:Number(container.dataset.index)}] : [];
    });
    const backControl = () => [...document.querySelectorAll('button,[role="button"]')]
        .find(e => visible(e) && ['Back','戻る','返回','返回上一頁','뒤로','Zurück','Atrás','Retour'].includes(e.getAttribute('aria-label')));
    const account = () => {
        const e = document.querySelector('[aria-label*="@"][role="button"],button[aria-label*="@"],a[aria-label*="@"]');
        return e?.getAttribute('aria-label')?.match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i)?.[0] || "";
    };
    const emit = complete => {
        if (!records.size) return;
        const data = {verified: true, complete, account: account(), devices: [...records.values()]};
        const next = JSON.stringify(data);
        if (next === serial) return;
        serial = next;
        try { FindBridgeGoogleData.postMessage(next); } catch (_) {}
    };
    const schedule = () => {
        if (scheduled) return;
        scheduled = true;
        // The bound refresh has no visible Window. DOM work must not depend on
        // requestAnimationFrame, which Chromium can suspend in that state.
        queueMicrotask(() => { scheduled = false; tick(); });
    };
    const armDeviceWatchdog = () => {
        clearTimeout(deviceWatchdog);
        // A missing/offline response must not stall the remaining catalog indefinitely.
        // This is a failure deadline; normal responses advance on DOM events immediately.
        deviceWatchdog = setTimeout(() => { expiredDevice = true; schedule(); }, 9000);
    };
    const armCycleWatchdog = () => {
        clearTimeout(cycleWatchdog);
        cycleWatchdog = setTimeout(() => { running = false; clearTimeout(deviceWatchdog); emit(true); }, 90000);
    };
    const read = () => {
        const buttons = list();
        for (const e of buttons) {
            const index = e.index, name = e.element.textContent.trim().slice(0, 200);
            if (index > 499 || !name) continue;
            const existing = records.get(index);
            if (!existing || existing.name !== name) records.set(index, {index, name});
            const image = e.element.querySelector('img');
            const imageUrl = image?.currentSrc || image?.src || '';
            if (imageUrl.length <= 4096) records.get(index).imageUrl = imageUrl;
        }
        for (const e of document.querySelectorAll('[data-location-lat][data-location-lng][data-slot-id]')) {
            // Slot identity comes from Google's marker, never from the map camera or last click.
            const slot = e.getAttribute('data-slot-id');
            if (!/^\d+(?:-\d+)?$/.test(slot || "")) continue;
            const record = records.get(Number(slot.split('-')[0]));
            const lat = Number(e.getAttribute('data-location-lat')), lon = Number(e.getAttribute('data-location-lng'));
            if (!record || !Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
            record.latitude = lat; record.longitude = lon;
        }
        const detail = records.get(current);
        const detailVisible = detail && !buttons.length &&
            [...document.querySelectorAll('h1,h2,h3,div,span')].some(e => visible(e) && e.textContent.trim() === detail.name);
        if (detailVisible) {
            const text = document.body.innerText;
            const battery = text.match(/(?:^|\n)\s*(\d{1,3})\s*%\s*(?:\n|$)/);
            if (battery && Number(battery[1]) <= 100) detail.battery = Number(battery[1]);
            const status = text.split('\n').map(s => s.trim()).find(s => /^(?:最終検知|最終確認|Last seen|Last located|Zuletzt|Dernière|Visto|Última|最后|上次|最後|최근|마지막|オフライン|デバイスに接続|Can.t reach|Unable to)/i.test(s));
            if (status) detail.status = status.slice(0, 200);
            // Relative labels and retained markers do not establish a timestamp or online state.
        }
        const busy = [...document.querySelectorAll('[aria-busy="true"],[role="progressbar"]')].some(visible);
        const settled = detail && (Number.isFinite(detail.latitude) || (detailVisible && !busy &&
            /^(?:オフライン|デバイスに接続でき|Can.t reach|Unable to|Offline)/i.test(detail.status || "")));
        return {buttons, settled};
    };
    const tick = () => {
        const prompt = !!dialog();
        if (prompt !== lastDialog) {
            lastDialog = prompt; state();
            clearTimeout(cycleWatchdog); clearTimeout(deviceWatchdog);
            if (!prompt && running) { armCycleWatchdog(); if (current !== null) armDeviceWatchdog(); }
        }
        if (prompt) return;
        const {buttons, settled} = read();
        if (!running) { emit(true); return; }
        if (waitingForCatalog) {
            if (!buttons.length) return;
            queue = requestedIndex >= 0 ? [requestedIndex] : buttons.map(e => e.index);
            waitingForCatalog = false;
        }
        if (current !== null) {
            if (!settled && !expiredDevice) { emit(false); return; }
            clearTimeout(deviceWatchdog); expiredDevice = false;
            current = null;
        }
        if (!queue.length && records.size) { running = false; clearTimeout(cycleWatchdog); emit(true); return; }
        if (!buttons.length) {
            const back = backControl();
            if (back && !backRequested) { backRequested = true; click(back); }
            emit(false); return;
        }
        backRequested = false;
        const next = queue.shift();
        const target = buttons.find(e => e.index === next);
        if (target) { current = next; expiredDevice = false; armDeviceWatchdog(); click(target.element); }
        else schedule();
        emit(false);
    };
    window.FindBridgeGoogleReader = {
        start(index) {
            const {buttons} = read();
            requestedIndex = index;
            waitingForCatalog = !buttons.length && !records.size;
            queue = index >= 0 ? [index] : buttons.map(e => e.index);
            clearTimeout(deviceWatchdog); expiredDevice = false;
            current = null; running = true; serial = ""; backRequested = false;
            armCycleWatchdog();
            if (!queue.length && records.size) queue = [...records.keys()];
            tick();
        },
        stop() { running = false; queue = []; clearTimeout(deviceWatchdog); clearTimeout(cycleWatchdog); },
        state,
    };
    // Start only after the authenticated device catalog has appeared.
    let begun = false;
    const changed = () => {
        if (document.readyState === 'loading') return;
        if (!begun && list().length) { begun = true; window.FindBridgeGoogleReader.start(requestedIndex); }
        else schedule();
    };
    new MutationObserver(changed).observe(document, {subtree: true, childList: true, attributes: true, characterData: true});
    for (const event of ['DOMContentLoaded', 'load', 'pageshow', 'resize']) addEventListener(event, changed);
    changed();
})();
