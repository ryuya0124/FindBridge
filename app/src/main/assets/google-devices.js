(() => {
    "use strict";
    if (location.origin !== "https://www.google.com" || !/^\/android\/find(?:\/u\/[0-9]{1,3})?\/?$/.test(location.pathname)) return;
    if (window.top !== window || window.FindBridgeGoogleReader) return;
    const records = new Map();
    let queue = [], current = null, serial = "", running = false, scheduled = false;
    let deviceWatchdog = null, cycleWatchdog = null, expiredDevice = false;
    let waitingForCatalog = false, requestedIndex = __FINDBRIDGE_GOOGLE_INITIAL_INDEX__;
    let automating = false, lastDialog = false, backRequested = false;
    let infoAttempted = false, infoAwaiting = false, infoClosing = false;
    const visible = e => e && e.getClientRects().length && getComputedStyle(e).visibility !== "hidden";
    const infoPanel = () => [...document.querySelectorAll('.Z3br3c')].find(visible);
    const dialog = () => [...document.querySelectorAll('[role="dialog"],[role="alertdialog"],[aria-modal="true"],dialog[open]')]
        .find(e => visible(e) && ([...e.querySelectorAll('input')].some(input => visible(input) &&
            (input.type === 'password' || input.autocomplete === 'one-time-code' || /PIN|暗証番号/i.test(input.getAttribute('aria-label') || ''))) ||
            !(infoAwaiting && infoPanel() && e.contains(infoPanel()))));
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
    const readInfo = record => {
        const panel = infoPanel();
        if (!panel) return false;
        const header = panel.querySelector('.KJqPCc')?.textContent.trim();
        if (header && header !== record.name) return false;
        const fields = [...panel.querySelectorAll('.e84yjf')].filter(visible).map(e => e.textContent.trim());
        const model = fields.find(s => s && !/[:：]/.test(s));
        if (model) record.model = model.slice(0, 200);
        const details = record.details ||= {};
        for (const field of fields) {
            let match;
            if ((match = field.match(/^IMEI(?:\s*\d+)?\s*[:：]\s*([\d\s-]{14,24})$/i))) details.imei = match[1].replace(/[\s-]/g, '');
            else if ((match = field.match(/^MEID\s*[:：]\s*([0-9a-f\s-]{14,24})$/i))) details.meid = match[1].replace(/[\s-]/g, '');
            else if ((match = field.match(/^(?:初回登録日|First registered|Registered on|首次注册|首次註冊|최초 등록|Erstmals registriert|Premier enregistrement|Primer registro)\s*[:：]\s*(.+)$/i))) details.firstRegistered = match[1].slice(0, 100);
            else if ((match = field.match(/^(?:有効化された日付|Activated on|激活日期|啟用日期|활성화 날짜|Aktiviert am|Date d.activation|Fecha de activación)\s*[:：]\s*(.+)$/i))) details.activatedAt = match[1].slice(0, 100);
            else if ((match = field.match(/^(?:メーカー|製造元|Manufacturer|制造商|製造商|제조업체|Hersteller|Fabricant|Fabricante)\s*[:：]\s*(.+)$/i))) details.manufacturer = match[1].slice(0, 100);
        }
        return true;
    };
    const account = () => {
        const e = document.querySelector('[aria-label*="@"][role="button"],button[aria-label*="@"],a[aria-label*="@"]');
        return e?.getAttribute('aria-label')?.match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i)?.[0] || "";
    };
    const emptyCatalog = () => !records.size && !list().length && account() &&
        ![...document.querySelectorAll('[aria-busy="true"],[role="progressbar"]')].some(visible) &&
        [...document.querySelectorAll('h1,h2,h3,p,div,span')].some(e => visible(e) &&
            /^(?:No devices found|You have no devices|No devices|デバイスが見つかりません(?:でした)?|デバイスがありません|登録されたデバイスはありません|Keine Geräte gefunden|Aucun appareil trouvé|No se encontraron dispositivos|기기를 찾을 수 없습니다|未找到设备|找不到裝置)[。.!！]?$/i.test(e.textContent.trim()));
    const emit = complete => {
        const empty = !!emptyCatalog();
        if (!records.size && !empty) return;
        const data = {verified: true, complete: empty || complete, requestedIndex, catalogEmpty: empty, account: account(), devices: [...records.values()]};
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
            const sound = [...document.querySelectorAll('[jsname="gnWSqc"]')].find(visible);
            const lock = [...document.querySelectorAll('[jsname="LVPyJb"]')].find(visible);
            const available = e => e && !e.disabled && e.getAttribute('aria-disabled') !== 'true' &&
                !e.querySelector('button:disabled,[aria-disabled="true"]');
            detail.soundPlaying = !!sound?.textContent.includes('stop_circle');
            detail.soundSupported = !!available(sound) &&
                (detail.soundPlaying || sound.textContent.includes('sound_sensing'));
            detail.lostModeSupported = !!available(lock);
            detail.lostModeEnabled = [...document.querySelectorAll('[jsname="SauT1d"]')].some(visible);
            const text = document.body.innerText;
            const details = detail.details ||= {};
            const wifi = [...document.querySelectorAll('[data-wifi]')].find(visible);
            const cellular = [...document.querySelectorAll('[data-cellular]')].find(visible);
            if (wifi?.parentElement?.textContent.trim()) details.network = wifi.parentElement.textContent.trim().slice(0, 200);
            if (cellular?.parentElement?.textContent.trim()) details.carrier = cellular.parentElement.textContent.trim().slice(0, 200);
            const battery = text.match(/(?:^|\n)\s*(\d{1,3})\s*%\s*(?:\n|$)/);
            if (battery && Number(battery[1]) <= 100) detail.battery = Number(battery[1]);
            const power = [...document.querySelectorAll('[data-battery]')].find(visible)?.getAttribute('data-battery') || '';
            if (/^\d{1,3}(?:-charging)?$/.test(power)) {
                detail.charging = power.endsWith('-charging');
                details.batteryState = detail.charging ? 'charging' : 'not_charging';
            }
            const status = text.split('\n').map(s => s.trim()).find(s => /^(?:最終検知|最終確認|Last seen|Last located|Zuletzt|Dernière|Visto|Última|最后|上次|最後|최근|마지막|オフライン|デバイスに接続|Can.t reach|Unable to)/i.test(s));
            const reportedConnection = document.getElementById('location-status');
            const connectionText = visible(reportedConnection) ? reportedConnection.textContent.trim() : '';
            if (connectionText || status) {
                detail.status = (connectionText || status).slice(0, 200);
                details.connectionState = detail.status;
            }
            for (const line of text.split('\n').map(s => s.trim())) {
                const field = line.match(/^(メーカー|製造元|Manufacturer|キャリア|通信事業者|Carrier|ネットワーク|Network|OS|Android version)\s*[:：]\s*(.+)$/i);
                if (field) {
                    const key = /メーカー|製造元|Manufacturer/i.test(field[1]) ? 'manufacturer' : /キャリア|通信事業者|Carrier/i.test(field[1]) ? 'carrier' : /OS|Android version/i.test(field[1]) ? 'operatingSystem' : 'network';
                    details[key] = field[2].slice(0, 200);
                }
            }
            // Relative labels and retained markers do not establish a timestamp or online state.
        }
        const busy = [...document.querySelectorAll('[aria-busy="true"],[role="progressbar"]')].some(visible);
        const settled = detail && detailVisible && (Number.isFinite(detail.latitude) || (!busy &&
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
        if (emptyCatalog()) {
            running = false; clearTimeout(deviceWatchdog); clearTimeout(cycleWatchdog); emit(true); return;
        }
        if (!running) { emit(true); return; }
        if (waitingForCatalog) {
            if (!buttons.length) return;
            queue = requestedIndex >= 0 ? [requestedIndex] : buttons.map(e => e.index);
            waitingForCatalog = false;
        }
        if (current !== null) {
            if (infoAwaiting) {
                const record = records.get(current);
                if (!infoClosing && record && readInfo(record)) {
                    const controls = [...document.querySelectorAll('button,[role="button"]')].filter(visible);
                    const close = controls.find(e => ['Close','閉じる','关闭','關閉','닫기','Schließen','Cerrar','Fermer'].includes(e.getAttribute('aria-label'))) ||
                        controls.find(e => e.textContent.trim() === 'close') || backControl();
                    if (close) {
                        // The outgoing panel can keep layout rectangles during its animation.
                        // The metadata is already captured; advance on navigation DOM events.
                        infoAwaiting = false; infoClosing = false; current = null;
                        clearTimeout(deviceWatchdog); expiredDevice = false; backRequested = false;
                        click(close); emit(false); schedule(); return;
                    }
                }
                if (infoClosing && !infoPanel()) { infoAwaiting = false; infoClosing = false; backRequested = false; }
                else if (!expiredDevice) { emit(false); return; }
                else infoAwaiting = false;
            }
            if (!settled && !expiredDevice) { emit(false); return; }
            if (settled && !infoAttempted) {
                infoAttempted = true;
                // Only opens Google's read-only device settings. No edit/save or remote action.
                const info = [...document.querySelectorAll('button,[role="button"]')].find(e => visible(e) &&
                    ['settings','info'].includes(e.textContent.trim()));
                if (info) { infoAwaiting = true; click(info); emit(false); return; }
            }
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
        if (target) { current = next; expiredDevice = false; infoAttempted = false; infoAwaiting = false; infoClosing = false; armDeviceWatchdog(); click(target.element); }
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
            infoAttempted = false; infoAwaiting = false; infoClosing = false;
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
