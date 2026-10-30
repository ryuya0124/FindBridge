(() => {
    "use strict";
    if (window.top !== window || location.origin !== 'https://www.google.com' ||
        !/^\/android\/find(?:\/u\/[0-9]{1,3})?\/?$/.test(location.pathname) || window.FindBridgeGoogleAction) return;
    const command = __FINDBRIDGE_GOOGLE_COMMAND__;
    let finished = false, scheduled = false, permitted = false, selecting = false;
    let selectedName = '', validated = false, announced = false, started = false, submitted = false;
    let verifying = false, timeout = null, credentialsVisible = false, automating = false;
    let pendingCommit = null;
    const stages = new Set();
    const visible = e => !!e && !!e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden';
    const enabled = e => visible(e) && !e.disabled && e.getAttribute('aria-disabled') !== 'true';
    const send = status => {
        try { FindBridgeGoogleActionState.postMessage(JSON.stringify({ticket: command.ticket, status})); } catch (_) {}
    };
    const done = status => {
        if (finished) return;
        finished = true; clearTimeout(timeout); observer.disconnect();
        command.phone = ''; command.message = '';
        send(status);
    };
    const arm = () => {
        clearTimeout(timeout);
        // A failure deadline, never a delay before progressing a ready document.
        timeout = setTimeout(() => done(submitted ? 'UNKNOWN' : 'UNAVAILABLE'), 45000);
    };
    const prompt = () => [...document.querySelectorAll('[role=dialog],[role=alertdialog],[aria-modal=true],dialog[open]')].find(visible);
    const credentialPrompt = p => p && [...p.querySelectorAll('input')].some(e => visible(e) &&
        (e.type === 'password' || e.autocomplete === 'one-time-code' || /PIN|暗証番号/i.test(e.getAttribute('aria-label') || '')));
    const verification = active => {
        if (credentialsVisible === active) return;
        credentialsVisible = active;
        try { FindBridgeGoogleState.postMessage(active ? 'verification' : 'loading'); } catch (_) {}
        if (active) clearTimeout(timeout); else arm();
    };
    // Only credential challenges are interactive. The operation itself stays native.
    for (const type of ['click','pointerdown','pointerup','touchstart','touchend','keydown'])
        addEventListener(type, event => {
            if (automating) return;
            const p = prompt();
            if (!credentialPrompt(p) || !p.contains(event.target)) {
                event.preventDefault(); event.stopImmediatePropagation();
            }
        }, {capture: true, passive: false});
    const click = e => {
        if (!enabled(e)) return false;
        automating = true;
        try { e.click(); } finally { automating = false; }
        return true;
    };
    const account = () => document.querySelector('[aria-label*="@"][role=button],button[aria-label*="@"],a[aria-label*="@"]')
        ?.getAttribute('aria-label')?.match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i)?.[0] || '';
    const accountMatches = () => account().toLowerCase() === command.account.toLowerCase();
    const catalog = () => [...document.querySelectorAll('[data-index]')].flatMap(container => {
        if (!visible(container) || !/^\d+$/.test(container.dataset.index)) return [];
        const e = container.matches('button,[role=button]') ? container :
            [...container.querySelectorAll('button,[role=button]')].find(n => visible(n) && n.querySelector('img'));
        return e && visible(e) && e.querySelector('img') ? [{element:e,index:Number(container.dataset.index)}] : [];
    });
    const targetVisible = () => validated && !catalog().length && accountMatches() &&
        [...document.querySelectorAll('h1,h2,h3,div,span')].some(e => visible(e) && e.textContent.trim() === selectedName);
    const control = name => [...document.querySelectorAll(`[jsname="${name}"]`)].filter(visible)
        .map(e => e.matches('button,[role=button]') ? e : e.querySelector('button,[role=button]') || e)[0];
    const icon = (e, name) => !!e && e.textContent.includes(name);
    const actionControl = () => {
        const e = control(command.type === 'MARK_LOST' ? 'LVPyJb' : 'gnWSqc');
        if (!enabled(e)) return null;
        if (command.type === 'RING' && !icon(e, 'sound_sensing')) return null;
        if (command.type === 'STOP_SOUND' && !icon(e, 'stop_circle')) return null;
        return e;
    };
    const select = async row => {
        if (verifying || selecting) return;
        verifying = true;
        const email = account(), name = row.element.textContent.trim().slice(0,200);
        try {
            const bytes = new TextEncoder().encode(`${email}:${row.index}:${name}`);
            const digest = await crypto.subtle.digest('SHA-256', bytes);
            const hash = [...new Uint8Array(digest).slice(0,12)].map(n => n.toString(16).padStart(2,'0')).join('');
            if (`google:${row.index}:${hash}` !== command.deviceId || !accountMatches()) return done('TARGET_CHANGED');
            selectedName = name; validated = true; selecting = true;
            if (!click(row.element)) done('UNAVAILABLE');
        } catch (_) { done('UNAVAILABLE'); }
        finally { verifying = false; schedule(); }
    };
    const positive = (p, final) => {
        const labels = final ? ['紛失としてマーク','Mark as lost','Mark as Lost','Als verloren markieren','Marquer comme perdu','Marcar como perdido','분실로 표시','标记为丢失','標示為遺失'] :
            ['次へ','Next','Weiter','Suivant','Siguiente','다음','下一步'];
        return [...p.querySelectorAll('button,[role=button]')].find(e => enabled(e) &&
            (labels.includes(e.textContent.trim()) || (!final &&
                ['data-mdc-dialog-action','data-dialog-action','data-action'].some(a => e.getAttribute(a) === 'ok'))));
    };
    const field = (p, name) => [...p.querySelectorAll(`[jsname="${name}"] input,input[jsname="${name}"],[jsname="${name}"] textarea,textarea[jsname="${name}"]`)]
        .find(e => enabled(e) && e.type !== 'password' && e.autocomplete !== 'one-time-code');
    const fill = (e, value) => {
        const proto = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto,'value').set.call(e,value);
        e.dispatchEvent(new Event('input',{bubbles:true}));
        e.dispatchEvent(new Event('change',{bubbles:true}));
    };
    const lostFlow = p => {
        if (!targetVisible()) return done('TARGET_CHANGED');
        if (p) {
            if (credentialPrompt(p)) return;
            const message = field(p,'HJT2Ge'), phone = field(p,'vcCt7');
            if (message && phone && !stages.has('submit')) {
                if (command.message.length > 80 || command.phone.length > 100) return done('UNAVAILABLE');
                if (stages.has('fill')) return;
                stages.add('fill'); fill(message,command.message); fill(phone,command.phone);
                // Input handlers can synchronously validate or replace the dialog.
                queueMicrotask(() => {
                    if (finished || !targetVisible() || prompt() !== p || credentialPrompt(p)) return done('TARGET_CHANGED');
                    const confirm = positive(p,true);
                    if (message.value !== command.message || phone.value !== command.phone || !confirm) return done('UNAVAILABLE');
                    pendingCommit = {p, message, phone, confirm};
                    stages.add('commitReady'); send('commitReady');
                });
            } else if (!stages.has('intro') && !p.querySelector('input,textarea') &&
                p.textContent.includes('wallet')) {
                const next = positive(p,false);
                if (next) { stages.add('intro'); click(next); }
            }
        } else if (submitted && visible(control('SauT1d'))) done('REQUESTED');
    };
    const tick = () => {
        if (finished || document.readyState === 'loading') return;
        const email = account();
        if (!email) return;
        if (!accountMatches()) return done('ACCOUNT_MISMATCH');
        const p = prompt(); verification(!!credentialPrompt(p));
        if (credentialPrompt(p)) return;
        if (!selecting) {
            const rows = catalog(); if (!rows.length) return;
            const row = rows.find(e => e.index === command.index);
            if (!row) return done('TARGET_CHANGED');
            select(row); return;
        }
        if (!targetVisible()) return;
        if (!started) {
            if (p) return;
            if (!actionControl()) return;
            if (!announced) { announced = true; send('ready'); }
            return;
        }
        if (command.type === 'MARK_LOST') return lostFlow(p);
        if (!submitted) return;
        const e = control('gnWSqc');
        if (command.type === 'RING' && icon(e,'stop_circle')) done('REQUESTED');
        if (command.type === 'STOP_SOUND' && icon(e,'sound_sensing') && enabled(e)) done('REQUESTED');
    };
    const schedule = () => {
        if (scheduled || finished) return;
        scheduled = true; queueMicrotask(() => { scheduled = false; tick(); });
    };
    const observer = new MutationObserver(schedule);
    window.FindBridgeGoogleAction = {
        execute(ticket) {
            if (finished || started || permitted || ticket !== command.ticket || !announced || !targetVisible()) return;
            const e = actionControl(); if (!e || prompt()) return done('TARGET_CHANGED');
            permitted = true; started = true;
            if (command.type !== 'MARK_LOST') { submitted = true; send('submitted'); }
            click(e); schedule();
        },
        commit(ticket) {
            if (finished || submitted || ticket !== command.ticket || command.type !== 'MARK_LOST' || !pendingCommit) return;
            const {p,message,phone,confirm} = pendingCommit;
            if (!targetVisible() || prompt() !== p || credentialPrompt(p) || !enabled(confirm) ||
                message.value !== command.message || phone.value !== command.phone) return done('TARGET_CHANGED');
            stages.add('submit'); submitted = true; pendingCommit = null;
            send('submitted'); click(confirm); schedule();
        },
        cancel() { done(submitted ? 'UNKNOWN' : 'CANCELED'); },
    };
    observer.observe(document,{subtree:true,childList:true,attributes:true,characterData:true});
    for (const event of ['DOMContentLoaded','load','pageshow']) addEventListener(event,schedule);
    arm(); schedule();
})();
