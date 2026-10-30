(() => {
    "use strict";
    if (location.origin !== "https://www.google.com" || !/^\/android\/find\/?$/.test(location.pathname)) return;
    if (window.top !== window || window.FindBridgeGoogleReader) return;
    const records = new Map();
    let queue = [], current = null, started = 0, lastChange = 0, serial = "", running = false;
    let waitingForCatalog = false, requestedIndex = __FINDBRIDGE_GOOGLE_INITIAL_INDEX__;
    let automating = false, lastDialog = false;
    const visible = e => e && e.getClientRects().length && getComputedStyle(e).visibility !== "hidden";
    const dialog = () => [...document.querySelectorAll('[role="dialog"],[aria-modal="true"],dialog[open]')].find(visible);
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
    const list = () => [...document.querySelectorAll('[role="button"][data-index]')]
        .filter(e => visible(e) && e.querySelector('img') && /^\d+$/.test(e.dataset.index));
    const control = name => [...document.querySelectorAll('button,[role="button"]')]
        .find(e => visible(e) && e.getAttribute('aria-label') === name);
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
    const read = () => {
        const buttons = list();
        for (const e of buttons) {
            const index = Number(e.dataset.index), name = e.textContent.trim().slice(0, 200);
            if (index > 499 || !name) continue;
            const existing = records.get(index);
            if (!existing || existing.name !== name) records.set(index, {index, name});
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
        if (detail && !buttons.length && performance.now() - lastChange > 500 &&
            [...document.querySelectorAll('h1,h2,h3,div,span')].some(e => visible(e) && e.textContent.trim() === detail.name)) {
            const text = document.body.innerText;
            const battery = text.match(/(?:^|\n)\s*(\d{1,3})\s*%\s*(?:\n|$)/);
            if (battery && Number(battery[1]) <= 100) detail.battery = Number(battery[1]);
            const status = text.split('\n').map(s => s.trim()).find(s => /^(?:最終検知|最終確認|Last seen|Last located|Zuletzt|Dernière|Visto|Última|最后|上次|最後|최근|마지막|オフライン|デバイスに接続|Can.t reach|Unable to)/i.test(s));
            if (status) detail.status = status.slice(0, 200);
            // Relative labels and retained markers do not establish a timestamp or online state.
        }
        return buttons;
    };
    const tick = () => {
        const prompt = !!dialog();
        if (prompt !== lastDialog) { lastDialog = prompt; state(); if (!prompt) started = performance.now(); }
        if (prompt) return;
        const buttons = read();
        if (!running) return;
        const now = performance.now();
        if (now - started > 90000) { running = false; emit(true); return; }
        if (waitingForCatalog) {
            if (!buttons.length) return;
            queue = requestedIndex >= 0 ? [requestedIndex] : buttons.map(e => Number(e.dataset.index));
            waitingForCatalog = false;
        }
        if (current !== null) {
            const record = records.get(current);
            if (now - lastChange < 2500 || (!Number.isFinite(record?.latitude) && now - lastChange < 9000)) { emit(false); return; }
            current = null;
        }
        if (!queue.length && records.size) { running = false; emit(true); return; }
        if (!buttons.length) {
            const back = control('Back');
            if (back && now - lastChange > 800) { click(back); lastChange = now; }
            emit(false); return;
        }
        const next = queue.shift();
        const target = buttons.find(e => Number(e.dataset.index) === next);
        if (target) { current = next; lastChange = now; click(target); }
        emit(false);
    };
    window.FindBridgeGoogleReader = {
        start(index) {
            const buttons = read();
            requestedIndex = index;
            waitingForCatalog = !buttons.length && !records.size;
            queue = index >= 0 ? [index] : buttons.map(e => Number(e.dataset.index));
            current = null; started = performance.now(); lastChange = 0; running = true; serial = "";
            if (!queue.length && records.size) queue = [...records.keys()];
            tick();
        },
        stop() { running = false; queue = []; },
        state,
    };
    // Start only after the authenticated device catalog has appeared.
    let begun = false;
    setInterval(() => {
        if (!begun && list().length) { begun = true; window.FindBridgeGoogleReader.start(requestedIndex); }
        else tick();
    }, 450);
})();
