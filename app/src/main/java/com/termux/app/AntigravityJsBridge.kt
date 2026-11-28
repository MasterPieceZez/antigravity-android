package com.termux.app

import android.content.Context
import android.content.SharedPreferences

/**
 * JavaScript snippets injected into the trusted Antigravity Hub WebView
 * for agent-completion notifications, quota exhaustion detection, blob/data downloads,
 * and Smart BiDi/RTL + optional Persian UI chrome support.
 *
 * Prefers WebViewCompat's origin-scoped WebMessageListener (`window.AntigravityBridge`)
 * and falls back to `window.AntigravityAndroid` only on legacy WebView implementations.
 */
object AntigravityJsBridge {

    const val PREFS_NAME = "antigravity_prefs"
    const val KEY_BIDI_RTL_ENABLED = "bidi_rtl_enabled"
    const val KEY_PERSIAN_UI_ENABLED = "persian_ui_enabled"
    const val DEFAULT_BIDI_RTL_ENABLED = true
    const val DEFAULT_PERSIAN_UI_ENABLED = false
    const val VAZIRMATN_ASSET_URL = "https://appassets.androidplatform.net/assets/fonts/Vazirmatn-NL.woff2"
    const val FA_DICT_ASSET_URL = "https://appassets.androidplatform.net/assets/i18n/fa.json"

    @Volatile
    private var cachedFaDictJson: String? = null

    fun preloadPersianDict(context: Context): String? {
        cachedFaDictJson?.let { return it }
        return runCatching {
            context.assets.open("i18n/fa.json").bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        }.getOrNull()?.takeIf { it.startsWith("{") && it.endsWith("}") }?.also {
            cachedFaDictJson = it
        }
    }

    fun isBidiRtlEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BIDI_RTL_ENABLED, DEFAULT_BIDI_RTL_ENABLED)

    fun isBidiRtlEnabled(context: Context): Boolean =
        isBidiRtlEnabled(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun setBidiRtlEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BIDI_RTL_ENABLED, enabled).apply()
    }

    fun isPersianUiEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_PERSIAN_UI_ENABLED, DEFAULT_PERSIAN_UI_ENABLED)

    fun isPersianUiEnabled(context: Context): Boolean =
        isPersianUiEnabled(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun setPersianUiEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PERSIAN_UI_ENABLED, enabled).apply()
    }

    val AGENT_BRIDGE_JS: String = """
        (function() {
            if (window.__agyNotifBridgeInstalled) return;
            window.__agyNotifBridgeInstalled = true;

            function sendNative(action, payload) {
                try {
                    if (window.AntigravityBridge && typeof window.AntigravityBridge.postMessage === 'function') {
                        var msg = Object.assign({ action: action }, payload || {});
                        window.AntigravityBridge.postMessage(JSON.stringify(msg));
                        return;
                    }
                    if (!window.AntigravityAndroid) return;
                    if (action === 'notify') {
                        window.AntigravityAndroid.notify(String(payload.title || 'Antigravity'), String(payload.body || ''));
                    } else if (action === 'agent_done') {
                        window.AntigravityAndroid.onAgentJobFinished(String(payload.summary || 'Agent finished its task'));
                    } else if (action === 'quota_exhausted' && typeof window.AntigravityAndroid.onQuotaExhausted === 'function') {
                        window.AntigravityAndroid.onQuotaExhausted(String(payload.reason || 'quota_exhausted'));
                    } else if (action === 'region_blocked' && typeof window.AntigravityAndroid.onRegionBlocked === 'function') {
                        window.AntigravityAndroid.onRegionBlocked(String(payload.reason || 'region_blocked'), Boolean(payload.isIpBlock));
                    } else if (action === 'theme_snapshot' && typeof window.AntigravityAndroid.onThemeSnapshot === 'function') {
                        window.AntigravityAndroid.onThemeSnapshot(JSON.stringify(payload || {}));
                    } else if (action === 'open_controls' && typeof window.AntigravityAndroid.openControls === 'function') {
                        window.AntigravityAndroid.openControls();
                    } else if (action === 'header_controls_state' && typeof window.AntigravityAndroid.onHeaderControlsState === 'function') {
                        window.AntigravityAndroid.onHeaderControlsState(Boolean(payload.mounted));
                    }
                } catch (e) {}
            }

            try {
                function AgyNotification(title, options) {
                    var body = (options && options.body) ? String(options.body) : '';
                    sendNative('notify', { title: String(title || 'Antigravity'), body: body });
                }
                AgyNotification.permission = 'granted';
                AgyNotification.requestPermission = function(cb) {
                    if (typeof cb === 'function') cb('granted');
                    return Promise.resolve('granted');
                };
                window.Notification = AgyNotification;
            } catch (e) {}

            var busySince = 0;
            var idleTimer = null;
            var checkScheduled = false;
            var lastNotifAt = 0;

            function isAgentBusy() {
                if (document.querySelector('[aria-busy="true"]')) return true;
                var btns = document.querySelectorAll('button, [role="button"]');
                for (var i = 0; i < btns.length; i++) {
                    var b = btns[i];
                    var label = ((b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.textContent || '')).trim().toLowerCase();
                    if (label === 'stop' || label === 'توقف' || label.indexOf('stop generating') !== -1 || label.indexOf('stop agent') !== -1 || label.indexOf('stop response') !== -1) {
                        if (b.offsetParent !== null) return true;
                    }
                }
                return false;
            }

            function checkState() {
                checkScheduled = false;
                var busy = isAgentBusy();
                var now = Date.now();
                if (busy) {
                    if (busySince === 0) busySince = now;
                    if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; }
                } else if (busySince > 0) {
                    var runDuration = now - busySince;
                    if (runDuration >= 2500 && !idleTimer) {
                        idleTimer = setTimeout(function() {
                            idleTimer = null;
                            if (!isAgentBusy() && busySince > 0) {
                                busySince = 0;
                                if (Date.now() - lastNotifAt > 5000) {
                                    lastNotifAt = Date.now();
                                    sendNative('agent_done', { summary: 'Agent finished its task' });
                                }
                            }
                        }, 2200);
                    } else if (runDuration < 2500) {
                        busySince = 0;
                    }
                }
            }

            function scheduleCheck() {
                if (document.hidden && busySince === 0) return;
                if (!checkScheduled) {
                    checkScheduled = true;
                    setTimeout(checkState, 600);
                }
            }

            var observer = new MutationObserver(scheduleCheck);
            function attachObserver() {
                if (document.body) {
                    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-busy', 'aria-label', 'title', 'disabled'] });
                } else {
                    setTimeout(attachObserver, 500);
                }
            }
            attachObserver();
            setInterval(function() {
                if (document.hidden && busySince === 0) return;
                checkState();
            }, 2000);

            // Quota exhaustion detection strictly from failed network responses (fetch & XHR)
            // to avoid false positives from chat text or rendered code snippets.
            var lastQuotaSignalAt = 0;
            function isQuotaText(txt) {
                if (!txt) return false;
                var lower = String(txt).toLowerCase();
                return lower.indexOf('resource_exhausted') !== -1 ||
                    lower.indexOf('quota exhausted') !== -1 ||
                    lower.indexOf('quota exceeded') !== -1 ||
                    lower.indexOf('exceeded your current quota') !== -1 ||
                    lower.indexOf('you have reached your') !== -1 ||
                    lower.indexOf('usage limit reached') !== -1 ||
                    lower.indexOf('rate limit exceeded') !== -1 ||
                    lower.indexOf('model quota') !== -1 ||
                    lower.indexOf('capacity exhausted') !== -1 ||
                    (lower.indexOf('429') !== -1 && (lower.indexOf('quota') !== -1 || lower.indexOf('rate') !== -1 || lower.indexOf('limit') !== -1));
            }
            function signalQuota(reason) {
                var now = Date.now();
                if (now - lastQuotaSignalAt < 15000) return;
                lastQuotaSignalAt = now;
                sendNative('quota_exhausted', { reason: String(reason || 'quota_exhausted').slice(0, 240) });
            }

            var lastRegionSignalAt = 0;
            function classifyRegionBlock(txt) {
                if (!txt) return null;
                var lower = String(txt).toLowerCase();
                if (lower.indexOf('18 years old or older') !== -1 ||
                    lower.indexOf('verified your age') !== -1) {
                    return null;
                }
                if (lower.indexOf('user location is not supported') !== -1) {
                    return { isIpBlock: true, reason: 'User location is not supported' };
                }
                if (lower.indexOf('"ineligible":true') !== -1 ||
                    lower.indexOf('"ineligible": true') !== -1 ||
                    lower.indexOf('"ineligible_tiers"') !== -1 ||
                    lower.indexOf('"ineligibletiers"') !== -1 ||
                    lower.indexOf('region is not supported') !== -1 ||
                    lower.indexOf('not available in your region') !== -1 ||
                    lower.indexOf('not available in your country') !== -1 ||
                    lower.indexOf('account is not eligible') !== -1 ||
                    lower.indexOf('unsupported region') !== -1) {
                    return { isIpBlock: false, reason: String(txt).slice(0, 240) };
                }
                return null;
            }
            function signalRegionIfMatched(txt) {
                var hit = classifyRegionBlock(txt);
                if (!hit) return;
                var now = Date.now();
                if (now - lastRegionSignalAt < 15000) return;
                lastRegionSignalAt = now;
                sendNative('region_blocked', hit);
            }

            if (window.fetch && !window.__agyFetchWrapped) {
                window.__agyFetchWrapped = true;
                var origFetch = window.fetch;
                window.fetch = function() {
                    return origFetch.apply(this, arguments).then(function(resp) {
                        try {
                            var ct = (resp && resp.headers && resp.headers.get('content-type')) || '';
                            if (resp && (resp.status === 429 || (!resp.ok && resp.status >= 400) || ct.indexOf('application/json') !== -1)) {
                                var cl = parseInt((resp.headers && resp.headers.get('content-length')) || '0', 10);
                                var maxLen = (resp.status === 429 || !resp.ok) ? 65536 : 16384;
                                if (!cl || cl <= maxLen) {
                                    resp.clone().text().then(function(body) {
                                        if (resp.status === 429) {
                                            if (!body || isQuotaText(body)) {
                                                signalQuota(body || 'HTTP 429 rate limit');
                                            }
                                        } else if (!resp.ok && isQuotaText(body)) {
                                            signalQuota(body);
                                        }
                                        signalRegionIfMatched(body);
                                    }).catch(function() {
                                        if (resp.status === 429) signalQuota('HTTP 429 rate limit');
                                    });
                                } else if (resp.status === 429) {
                                    signalQuota('HTTP 429 rate limit');
                                }
                            }
                        } catch (e) {}
                        return resp;
                    });
                };
            }

            if (window.XMLHttpRequest && !window.__agyXhrWrapped) {
                window.__agyXhrWrapped = true;
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function() {
                    if (!this.__agyXhrTracked) {
                        this.__agyXhrTracked = true;
                        this.addEventListener('load', function() {
                            try {
                                if (this.status === 429) {
                                    var txt429 = (typeof this.responseText === 'string') ? this.responseText : '';
                                    if (!txt429 || isQuotaText(txt429)) {
                                        signalQuota(txt429 || 'XHR 429 rate limit');
                                    }
                                } else if (typeof this.responseText === 'string' && this.responseText.length <= 65536) {
                                    if (this.status >= 400 && isQuotaText(this.responseText)) {
                                        signalQuota(this.responseText);
                                    }
                                    signalRegionIfMatched(this.responseText);
                                }
                            } catch (e) {}
                        });
                    }
                    return origOpen.apply(this, arguments);
                };
            }

            // Snapshot Hub CSS theme (surfaces, bevel borders, radius, drop shadow, text)
            var lastThemeSnapAt = 0;
            function isOpaqueColor(c) {
                return c && c !== 'transparent' && c !== 'rgba(0, 0, 0, 0)';
            }
            function snapshotHubTheme() {
                var now = Date.now();
                if (now - lastThemeSnapAt < 10000) return;
                if (!document.body) return;
                lastThemeSnapAt = now;
                try {
                    var bodyCs = getComputedStyle(document.body);
                    var rootCs = getComputedStyle(document.documentElement);
                    var surfaceBg = isOpaqueColor(bodyCs.backgroundColor) ? bodyCs.backgroundColor : rootCs.backgroundColor;
                    var cardEl = document.querySelector('[class*="bg-card"], [class*="bg-popover"], [class*="card"], [class*="panel"], header, nav, aside, button');
                    var cardCs = cardEl ? getComputedStyle(cardEl) : bodyCs;
                    var cardBg = isOpaqueColor(cardCs.backgroundColor) ? cardCs.backgroundColor : surfaceBg;
                    var borderColor = isOpaqueColor(cardCs.borderColor) ? cardCs.borderColor : (isOpaqueColor(cardCs.borderTopColor) ? cardCs.borderTopColor : '');
                    var borderW = parseFloat(cardCs.borderTopWidth || cardCs.borderWidth || '1') || 1;
                    var radius = parseFloat(cardCs.borderRadius || '18') || 18;
                    var hasShadow = (cardCs.boxShadow && cardCs.boxShadow !== 'none');
                    var btnEl = document.querySelector('button[class*="bg-primary"], [class*="bg-primary"], button[class*="primary"], a[class*="primary"]');
                    var btnCs = btnEl ? getComputedStyle(btnEl) : cardCs;
                    var accentColor = isOpaqueColor(btnCs.backgroundColor) ? btnCs.backgroundColor : '';
                    var textPrimary = bodyCs.color || '';
                    var mutedEl = document.querySelector('[class*="text-muted"], [class*="muted"], [class*="secondary"], small');
                    var textSecondary = mutedEl ? getComputedStyle(mutedEl).color : textPrimary;
                    sendNative('theme_snapshot', {
                        surfaceBg: surfaceBg || '',
                        cardBg: cardBg || '',
                        borderColor: borderColor || '',
                        borderWidthDp: borderW,
                        cornerRadiusDp: radius,
                        shadowDp: hasShadow ? 14 : 10,
                        accentColor: accentColor || '',
                        textPrimary: textPrimary,
                        textSecondary: textSecondary
                    });
                } catch (e) {}
            }
            setTimeout(snapshotHubTheme, 900);
            try {
                var themeObs = new MutationObserver(function() {
                    setTimeout(snapshotHubTheme, 250);
                });
                themeObs.observe(document.documentElement, {
                    attributes: true,
                    attributeFilter: ['class', 'style', 'data-theme', 'data-color-mode']
                });
            } catch (e) {}

            // 1:1 Theme-matched Control Center button burned into Hub top header bar
            function isFaUi() {
                return !!(window.__agyBidiCfg && window.__agyBidiCfg.enablePersianUi);
            }
            var lastHeaderMounted = null;
            var headerMountedAtLeastOnce = false;
            var headerBootStartedAt = Date.now();
            function reportHeaderMounted(mounted) {
                if (mounted) headerMountedAtLeastOnce = true;
                if (!mounted && !headerMountedAtLeastOnce && (Date.now() - headerBootStartedAt) < 1800) {
                    return;
                }
                if (lastHeaderMounted === mounted) return;
                lastHeaderMounted = mounted;
                sendNative('header_controls_state', { mounted: mounted });
            }
            function findHeaderSettingsBtn() {
                var btn = document.querySelector('button[data-testid="mobile-open-settings"]');
                if (btn && btn.offsetParent !== null) return btn;
                var candidates = document.querySelectorAll('header button, [data-testid="mobile-search-conversations"] ~ button, button[aria-label="Settings"], button[aria-label="تنظیمات"]');
                for (var i = 0; i < candidates.length; i++) {
                    var c = candidates[i];
                    if (c.id === 'agy-header-controls-btn' || c.offsetParent === null) continue;
                    var r = c.getBoundingClientRect();
                    if (r.top >= 0 && r.top <= 120 && r.width > 0 && r.height > 0) {
                        var lbl = (c.getAttribute('aria-label') || c.getAttribute('title') || '').trim().toLowerCase();
                        if (lbl === 'settings' || lbl === 'تنظیمات' || c.getAttribute('data-testid') === 'mobile-open-settings') {
                            return c;
                        }
                    }
                }
                return null;
            }
            function ensureHeaderControlsButton() {
                try {
                    var settingsBtn = findHeaderSettingsBtn();
                    var existing = document.getElementById('agy-header-controls-btn');
                    if (!settingsBtn || !settingsBtn.parentNode) {
                        if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
                        reportHeaderMounted(false);
                        return;
                    }
                    var fa = isFaUi();
                    var aria = fa ? 'مرکز کنترل' : 'Control Center';
                    var title = fa ? 'مرکز کنترل آنتی‌گرویتی' : 'Antigravity Control Center';
                    if (!existing) {
                        var btn = document.createElement('button');
                        btn.id = 'agy-header-controls-btn';
                        btn.type = 'button';
                        btn.setAttribute('data-testid', 'agy-header-controls');
                        btn.setAttribute('aria-label', aria);
                        btn.setAttribute('title', title);
                        btn.className = settingsBtn.className || '';
                        btn.style.webkitAppRegion = 'no-drag';
                        var refSvg = settingsBtn.querySelector('svg');
                        var svgW = (refSvg && refSvg.getAttribute('width')) || '20';
                        var svgH = (refSvg && refSvg.getAttribute('height')) || '20';
                        var svgCls = (refSvg && refSvg.getAttribute('class')) || '';
                        btn.innerHTML =
                            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="' + svgW + '" height="' + svgH + '" fill="currentColor" aria-hidden="true" class="' + svgCls + '">' +
                            '<path d="M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z"/>' +
                            '</svg>';
                        btn.addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            sendNative('open_controls', {});
                        });
                        settingsBtn.parentNode.insertBefore(btn, settingsBtn.nextSibling);
                    } else {
                        if (existing.className !== (settingsBtn.className || '')) {
                            existing.className = settingsBtn.className || '';
                        }
                        if (existing.previousSibling !== settingsBtn) {
                            settingsBtn.parentNode.insertBefore(existing, settingsBtn.nextSibling);
                        }
                        existing.setAttribute('aria-label', aria);
                        existing.setAttribute('title', title);
                    }
                    reportHeaderMounted(true);
                } catch (e) {}
            }

            // Inline Microphone Level Meter & Record/Playback Tester inside Settings -> Voice Mode
            var micState = window.__agyMicTester || (window.__agyMicTester = {
                stream: null,
                audioCtx: null,
                analyser: null,
                rafId: 0,
                recorder: null,
                chunks: [],
                recTimer: 0,
                recAutoStop: 0,
                recStartedAt: 0,
                mode: 'idle', // 'idle' | 'meter' | 'recording' | 'playing'
                audio: null,
                blobUrl: null,
                abortPlayback: false
            });
            function stopMicTracksAndAudioCtx() {
                if (micState.rafId) {
                    cancelAnimationFrame(micState.rafId);
                    micState.rafId = 0;
                }
                if (micState.stream) {
                    try {
                        micState.stream.getTracks().forEach(function(t) { try { t.stop(); } catch (e) {} });
                    } catch (e) {}
                    micState.stream = null;
                }
                if (micState.audioCtx) {
                    try { micState.audioCtx.close().catch(function(){}); } catch (e) {}
                    micState.audioCtx = null;
                }
                micState.analyser = null;
                var fill = document.getElementById('agy-mic-meter-fill');
                if (fill) fill.style.width = '0%';
            }
            function stopMicPlayback() {
                if (micState.audio) {
                    try { micState.audio.pause(); micState.audio.src = ''; } catch (e) {}
                    micState.audio = null;
                }
                if (micState.blobUrl) {
                    try { URL.revokeObjectURL(micState.blobUrl); } catch (e) {}
                    micState.blobUrl = null;
                }
            }
            function stopAllMicActivity(abortRecPlayback) {
                if (micState.recTimer) { clearInterval(micState.recTimer); micState.recTimer = 0; }
                if (micState.recAutoStop) { clearTimeout(micState.recAutoStop); micState.recAutoStop = 0; }
                if (micState.recorder && micState.recorder.state !== 'inactive') {
                    micState.abortPlayback = !!abortRecPlayback;
                    try { micState.recorder.stop(); } catch (e) {}
                } else {
                    stopMicTracksAndAudioCtx();
                }
                if (abortRecPlayback) {
                    stopMicPlayback();
                }
                if (abortRecPlayback || micState.mode !== 'recording') {
                    micState.mode = 'idle';
                }
                updateMicTesterUi();
            }
            function updateMicTesterUi(customStatus) {
                var card = document.getElementById('agy-mic-tester-card');
                if (!card) return;
                var fa = isFaUi();
                card.setAttribute('dir', fa ? 'rtl' : 'ltr');
                var titleEl = document.getElementById('agy-mic-tester-title');
                var statusEl = document.getElementById('agy-mic-tester-status');
                var liveBtn = document.getElementById('agy-mic-live-btn');
                var recBtn = document.getElementById('agy-mic-rec-btn');
                if (titleEl) titleEl.textContent = fa ? 'تست میکروفون' : 'Test Mic';
                if (liveBtn) {
                    liveBtn.textContent = (micState.mode === 'meter')
                        ? (fa ? 'توقف تست' : 'Stop Meter')
                        : (fa ? 'تست سطح صدا' : 'Live Meter');
                }
                if (recBtn) {
                    if (micState.mode === 'recording') {
                        recBtn.textContent = fa ? 'توقف و پخش' : 'Stop & Play';
                    } else if (micState.mode === 'playing') {
                        recBtn.textContent = fa ? 'توقف پخش' : 'Stop Playback';
                    } else {
                        recBtn.textContent = fa ? 'ضبط و پخش' : 'Record & Play';
                    }
                }
                if (statusEl) {
                    if (customStatus) {
                        statusEl.textContent = customStatus;
                    } else if (micState.mode === 'meter') {
                        statusEl.textContent = fa ? 'در حال دریافت صدا...' : 'Listening...';
                    } else if (micState.mode === 'playing') {
                        statusEl.textContent = fa ? 'در حال پخش صدای ضبط‌شده...' : 'Playing back...';
                    } else if (micState.mode === 'idle') {
                        statusEl.textContent = fa ? 'آماده' : 'Ready';
                    }
                }
            }
            function startAnalyserLoop(stream) {
                try {
                    var AudioCtx = window.AudioContext || window.webkitAudioContext;
                    if (!AudioCtx) return;
                    var ctx = new AudioCtx();
                    if (ctx.state === 'suspended') {
                        ctx.resume().catch(function(){});
                    }
                    var src = ctx.createMediaStreamSource(stream);
                    var analyser = ctx.createAnalyser();
                    analyser.fftSize = 256;
                    analyser.smoothingTimeConstant = 0.72;
                    src.connect(analyser);
                    micState.audioCtx = ctx;
                    micState.analyser = analyser;
                    var data = new Uint8Array(analyser.frequencyBinCount);
                    function tick() {
                        if (!micState.analyser || (micState.mode !== 'meter' && micState.mode !== 'recording')) return;
                        analyser.getByteTimeDomainData(data);
                        var sum = 0;
                        for (var i = 0; i < data.length; i++) {
                            var v = (data[i] - 128) / 128;
                            sum += v * v;
                        }
                        var rms = Math.sqrt(sum / data.length);
                        var pct = Math.min(100, Math.max(2, Math.round(rms * 320)));
                        var fill = document.getElementById('agy-mic-meter-fill');
                        if (fill) fill.style.width = pct + '%';
                        micState.rafId = requestAnimationFrame(tick);
                    }
                    micState.rafId = requestAnimationFrame(tick);
                } catch (e) {}
            }
            function handleMicError(err) {
                stopAllMicActivity(true);
                var fa = isFaUi();
                var msg = (err && err.name === 'NotAllowedError')
                    ? (fa ? 'دسترسی میکروفون رد شد' : 'Mic permission denied')
                    : (fa ? 'خطا در دسترسی به میکروفون' : 'Microphone unavailable');
                updateMicTesterUi(msg);
            }
            function startLiveMicMeter() {
                if (micState.mode === 'meter') {
                    stopAllMicActivity(true);
                    return;
                }
                stopAllMicActivity(true);
                if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                    handleMicError(null);
                    return;
                }
                micState.mode = 'meter';
                updateMicTesterUi();
                navigator.mediaDevices.getUserMedia({ audio: true }).then(function(stream) {
                    if (micState.mode !== 'meter' || !document.getElementById('agy-mic-tester-card')) {
                        stream.getTracks().forEach(function(t) { try { t.stop(); } catch (e) {} });
                        return;
                    }
                    micState.stream = stream;
                    startAnalyserLoop(stream);
                    updateMicTesterUi();
                }).catch(handleMicError);
            }
            function startRecordAndPlay() {
                if (micState.mode === 'recording') {
                    micState.abortPlayback = false;
                    if (micState.recTimer) { clearInterval(micState.recTimer); micState.recTimer = 0; }
                    if (micState.recAutoStop) { clearTimeout(micState.recAutoStop); micState.recAutoStop = 0; }
                    if (micState.recorder && micState.recorder.state !== 'inactive') {
                        try { micState.recorder.stop(); } catch (e) { stopAllMicActivity(true); }
                    } else {
                        stopAllMicActivity(true);
                    }
                    return;
                }
                if (micState.mode === 'playing') {
                    stopAllMicActivity(true);
                    return;
                }
                stopAllMicActivity(true);
                if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || typeof MediaRecorder === 'undefined') {
                    handleMicError(null);
                    return;
                }
                micState.mode = 'recording';
                micState.abortPlayback = false;
                micState.chunks = [];
                var fa = isFaUi();
                updateMicTesterUi(fa ? 'در حال ضبط (۰ / ۱۰ ثانیه)...' : 'Recording (0s / 10s)...');
                navigator.mediaDevices.getUserMedia({ audio: true }).then(function(stream) {
                    if (micState.mode !== 'recording' || !document.getElementById('agy-mic-tester-card')) {
                        stream.getTracks().forEach(function(t) { try { t.stop(); } catch (e) {} });
                        return;
                    }
                    micState.stream = stream;
                    startAnalyserLoop(stream);
                    var opts = {};
                    if (typeof MediaRecorder.isTypeSupported === 'function') {
                        if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) opts.mimeType = 'audio/webm;codecs=opus';
                        else if (MediaRecorder.isTypeSupported('audio/webm')) opts.mimeType = 'audio/webm';
                    }
                    var rec = new MediaRecorder(stream, opts);
                    micState.recorder = rec;
                    micState.recStartedAt = Date.now();
                    rec.ondataavailable = function(ev) {
                        if (ev.data && ev.data.size > 0) micState.chunks.push(ev.data);
                    };
                    rec.onstop = function() {
                        if (micState.recTimer) { clearInterval(micState.recTimer); micState.recTimer = 0; }
                        if (micState.recAutoStop) { clearTimeout(micState.recAutoStop); micState.recAutoStop = 0; }
                        stopMicTracksAndAudioCtx();
                        var aborted = micState.abortPlayback;
                        var chunks = micState.chunks;
                        micState.chunks = [];
                        micState.recorder = null;
                        if (aborted || !chunks || chunks.length === 0) {
                            micState.mode = 'idle';
                            updateMicTesterUi();
                            return;
                        }
                        var blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' });
                        stopMicPlayback();
                        var url = URL.createObjectURL(blob);
                        micState.blobUrl = url;
                        var audio = new Audio(url);
                        micState.audio = audio;
                        micState.mode = 'playing';
                        updateMicTesterUi();
                        audio.onended = function() {
                            stopMicPlayback();
                            micState.mode = 'idle';
                            updateMicTesterUi();
                        };
                        audio.onerror = function() {
                            stopMicPlayback();
                            micState.mode = 'idle';
                            updateMicTesterUi(isFaUi() ? 'خطا در پخش صدا' : 'Playback failed');
                        };
                        audio.play().catch(function() {
                            stopMicPlayback();
                            micState.mode = 'idle';
                            updateMicTesterUi(isFaUi() ? 'خطا در پخش صدا' : 'Playback failed');
                        });
                    };
                    rec.start(200);
                    micState.recTimer = setInterval(function() {
                        if (micState.mode !== 'recording') return;
                        var sec = Math.min(10, Math.floor((Date.now() - micState.recStartedAt) / 1000));
                        updateMicTesterUi(isFaUi() ? ('در حال ضبط (' + sec + ' / ۱۰ ثانیه)...') : ('Recording (' + sec + 's / 10s)...'));
                    }, 250);
                    micState.recAutoStop = setTimeout(function() {
                        if (micState.mode === 'recording' && micState.recorder && micState.recorder.state !== 'inactive') {
                            micState.abortPlayback = false;
                            try { micState.recorder.stop(); } catch (e) {}
                        }
                    }, 10000);
                }).catch(handleMicError);
            }
            function ensureMicTesterCard() {
                try {
                    var micCtrl = document.querySelector('[data-testid="voice-settings-mic"]');
                    var micLabel = document.getElementById('voice-microphone-label');
                    var anchor = micCtrl || micLabel;
                    if (!anchor || anchor.offsetParent === null) {
                        if (micState.mode !== 'idle' || micState.stream) {
                            stopAllMicActivity(true);
                        }
                        return;
                    }
                    var micRow = (micCtrl && micCtrl.parentElement) || (micLabel && micLabel.closest('[class*="justify-between"]'));
                    if (!micRow || !micRow.parentNode) return;
                    var existing = document.getElementById('agy-mic-tester-card');
                    if (existing) {
                        if (existing.previousSibling !== micRow) {
                            micRow.parentNode.insertBefore(existing, micRow.nextSibling);
                        }
                        updateMicTesterUi();
                        return;
                    }
                    var card = document.createElement('div');
                    card.id = 'agy-mic-tester-card';
                    card.className = 'mx-3 my-1.5 px-3 py-2.5 rounded-lg border text-sm';
                    card.style.cssText = 'background:var(--secondary,rgba(128,128,128,0.08));border-color:var(--border,rgba(128,128,128,0.24));';
                    card.innerHTML =
                        '<div style="display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:8px;">' +
                            '<span id="agy-mic-tester-title" style="font-weight:600;font-size:13px;">Test Mic</span>' +
                            '<span id="agy-mic-tester-status" style="font-size:11.5px;opacity:0.75;">Ready</span>' +
                        '</div>' +
                        '<div style="height:6px;width:100%;border-radius:999px;background:rgba(128,128,128,0.24);overflow:hidden;margin-bottom:9px;">' +
                            '<div id="agy-mic-meter-fill" style="height:100%;width:0%;border-radius:999px;background:var(--primary,#3B82F6);transition:width 60ms linear;"></div>' +
                        '</div>' +
                        '<div style="display:flex;align-items:center;gap:8px;">' +
                            '<button id="agy-mic-live-btn" type="button" class="px-2.5 py-1 rounded-md border text-xs font-medium bg-background hover:bg-muted transition-colors" style="border-color:var(--border,rgba(128,128,128,0.3));cursor:pointer;">Live Meter</button>' +
                            '<button id="agy-mic-rec-btn" type="button" class="px-2.5 py-1 rounded-md border text-xs font-medium bg-background hover:bg-muted transition-colors" style="border-color:var(--border,rgba(128,128,128,0.3));cursor:pointer;">Record & Play</button>' +
                        '</div>';
                    micRow.parentNode.insertBefore(card, micRow.nextSibling);
                    var liveBtn = document.getElementById('agy-mic-live-btn');
                    var recBtn = document.getElementById('agy-mic-rec-btn');
                    if (liveBtn) liveBtn.addEventListener('click', function(e) { e.preventDefault(); startLiveMicMeter(); });
                    if (recBtn) recBtn.addEventListener('click', function(e) { e.preventDefault(); startRecordAndPlay(); });
                    updateMicTesterUi();
                } catch (e) {}
            }
            if (!window.__agyMicLifecycleHooked) {
                window.__agyMicLifecycleHooked = true;
                document.addEventListener('visibilitychange', function() {
                    if (document.hidden) stopAllMicActivity(true);
                });
                window.addEventListener('pagehide', function() {
                    stopAllMicActivity(true);
                });
            }

            var uiInjectScheduled = false;
            function runUiInjections() {
                uiInjectScheduled = false;
                ensureHeaderControlsButton();
                ensureMicTesterCard();
            }
            function scheduleUiInjections() {
                if (uiInjectScheduled) return;
                uiInjectScheduled = true;
                setTimeout(runUiInjections, 120);
            }
            var uiObs = new MutationObserver(scheduleUiInjections);
            function attachUiObserver() {
                if (document.body) {
                    uiObs.observe(document.body, { childList: true, subtree: true });
                    runUiInjections();
                } else {
                    setTimeout(attachUiObserver, 300);
                }
            }
            attachUiObserver();
            setTimeout(runUiInjections, 700);
            setTimeout(runUiInjections, 1900);
            setInterval(function() {
                if (!document.hidden) runUiInjections();
            }, 2000);
        })();
    """.trimIndent()

    private fun quoteJsString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '<' -> append("\\u003C")
                '>' -> append("\\u003E")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(ch)
            }
        }
        append('"')
    }

    fun buildBlobDownloadJs(url: String, guessedName: String): String {
        val qUrl = quoteJsString(url)
        val qName = quoteJsString(guessedName)
        return """
            (function() {
                fetch($qUrl).then(function(r){ return r.blob(); }).then(function(b){
                    if (b.size > 25 * 1024 * 1024) {
                        if (window.AntigravityBridge && typeof window.AntigravityBridge.postMessage === 'function') {
                            window.AntigravityBridge.postMessage(JSON.stringify({ action: 'download_too_large' }));
                        } else if (window.AntigravityAndroid) {
                            window.AntigravityAndroid.notifyDownloadTooLarge();
                        }
                        return;
                    }
                    var fr = new FileReader();
                    fr.onloadend = function() {
                        if (!fr.result) return;
                        if (window.AntigravityBridge && typeof window.AntigravityBridge.postMessage === 'function') {
                            window.AntigravityBridge.postMessage(JSON.stringify({
                                action: 'save_base64',
                                filename: $qName,
                                data: String(fr.result)
                            }));
                        } else if (window.AntigravityAndroid) {
                            window.AntigravityAndroid.saveDownloadedBase64($qName, String(fr.result));
                        }
                    };
                    fr.readAsDataURL(b);
                }).catch(function(){});
            })();
        """.trimIndent()
    }

    fun buildPasteImageJs(fileName: String, mimeType: String, base64Data: String): String {
        val qName = quoteJsString(fileName)
        val qMime = quoteJsString(mimeType)
        val qB64 = quoteJsString(base64Data)
        return """
            (function() {
                try {
                    var b64 = $qB64;
                    var bin = atob(b64);
                    var len = bin.length;
                    var bytes = new Uint8Array(len);
                    for (var i = 0; i < len; i++) {
                        bytes[i] = bin.charCodeAt(i);
                    }
                    var mime = $qMime || 'image/png';
                    var file = new File([bytes], $qName, { type: mime, lastModified: Date.now() });
                    var dt = new DataTransfer();
                    dt.items.add(file);
                    var active = document.activeElement;
                    var target = null;
                    if (active && (active.tagName === 'TEXTAREA' || active.tagName === 'INPUT' || active.isContentEditable)) {
                        target = active;
                    }
                    if (!target) {
                        target = document.querySelector('textarea[placeholder], [contenteditable="true"][role="textbox"], [contenteditable="true"], textarea');
                    }
                    if (!target) return;
                    try { target.focus(); } catch (e) {}
                    var evt = new ClipboardEvent('paste', {
                        clipboardData: dt,
                        bubbles: true,
                        cancelable: true
                    });
                    if (!evt.clipboardData || !evt.clipboardData.files || evt.clipboardData.files.length === 0) {
                        try {
                            Object.defineProperty(evt, 'clipboardData', { value: dt });
                        } catch (e) {}
                    }
                    target.dispatchEvent(evt);
                } catch (e) {}
            })();
        """.trimIndent()
    }

    /**
     * Builds the idempotent, zero-flash JavaScript for Smart BiDi/RTL layout,
     * offline Vazirmatn font loading, and React-safe Persian UI chrome translation.
     */
    fun buildPersianBidiJs(context: Context, enableBidi: Boolean, enablePersianUi: Boolean): String =
        buildPersianBidiJs(
            enableBidi = enableBidi,
            enablePersianUi = enablePersianUi,
            inlineFaJson = if (enablePersianUi) preloadPersianDict(context) else null
        )

    @JvmOverloads
    fun buildPersianBidiJs(
        enableBidi: Boolean,
        enablePersianUi: Boolean,
        inlineFaJson: String? = if (enablePersianUi) cachedFaDictJson else null
    ): String {
        val qFontUrl = quoteJsString(VAZIRMATN_ASSET_URL)
        val qDictUrl = quoteJsString(FA_DICT_ASSET_URL)
        val embeddedDict = if (enablePersianUi && !inlineFaJson.isNullOrBlank()) inlineFaJson else "null"
        return """
            (function() {
                var enableBidi = $enableBidi;
                var enablePersianUi = $enablePersianUi;
                var inlineDict = $embeddedDict;
                window.__agyBidiCfg = { enableBidi: enableBidi, enablePersianUi: enablePersianUi };

                var st = window.__agyBidiState || (window.__agyBidiState = {
                    origText: new WeakMap(),
                    origAttr: new WeakMap(),
                    origDir: new WeakMap(),
                    origChromeDir: new WeakMap(),
                    faDict: null,
                    faDictLoading: false,
                    observerInstalled: false,
                    observer: null,
                    applying: false
                });

                if (inlineDict && typeof inlineDict === 'object') {
                    st.faDict = inlineDict;
                }

                var STYLE_ID = 'agy-bidi-style';
                var BIDI_TARGET_SEL = 'p, li, h1, h2, h3, h4, h5, h6, blockquote, textarea, [contenteditable="true"], [class*="prose"], [class*="markdown"], [data-testid*="message"], [class*="message-content"], [class*="message-body"], [class*="chat-message"]';
                var CODE_LOCK_SEL = 'pre, code, kbd, samp, .monaco-editor, .xterm, [class*="diff-"], [class*="-diff"], [class*="diffEditor"], [class*="DiffEditor"], [class*="bg-diffEditor"], [class*="terminal-"], [class*="-terminal"], .terminal, [data-testid*="terminal"], [data-testid*="diff"]';
                var HARD_SKIP_SEL = 'pre, code, kbd, samp, script, style, noscript, svg, .monaco-editor, .xterm, [class*="diff-"], [class*="-diff"], [class*="diffEditor"], [class*="DiffEditor"], [class*="bg-diffEditor"], [class*="terminal-"], [class*="-terminal"], .terminal, [data-testid*="terminal"], [data-testid*="diff"]';
                var CHAT_PROSE_SEL = '[data-testid="live-user-turn"], [data-testid="live-agent-turn"], [data-testid*="message-content"], [class*="message-content"], [class*="message-body"], .prose, [class*="prose "], [class*=" prose"], [class*="markdown"]';
                var CHROME_CONTAINER_SEL = '[role="dialog"], [role="menu"], [role="listbox"], [data-radix-popper-content-wrapper], nav, aside, header, [class*="settings"], [data-testid*="settings"], [class*="sidebar"], [class*="toolbar"], [class*="popover"], [class*="sheet"]';
                var PANEL_RTL_SEL = '[role="dialog"], [role="menu"], [role="listbox"], [data-radix-popper-content-wrapper], [class*="popover"], [class*="sheet"], [class*="settings"]';
                var KBD_BADGE_RE = /^(?:Ctrl|Cmd|Alt|Shift|Meta|CmdOrCtrl|Opt|⌘|⌥|⌃|⇧)(?:\s*\+\s*[^\s]+)+$/i;
                var LTR_VALUE_RE = /^(?:(?:Ctrl|Cmd|Alt|Shift|Meta|CmdOrCtrl|Opt|⌘|⌥|⌃|⇧)(?:\s*\+\s*[^\s]+)+|#[0-9a-fA-F]{3,8}|-?\d+(?:\.\d+)?(?:px|rem|em|vh|vw|%|ms|s))$/i;

                function ensureFaDictLoaded() {
                    if (st.faDict || st.faDictLoading) return;
                    st.faDictLoading = true;
                    fetch($qDictUrl, { cache: 'force-cache' })
                        .then(function(r) { return r.ok ? r.json() : null; })
                        .then(function(dict) {
                            st.faDictLoading = false;
                            if (dict && typeof dict === 'object') {
                                st.faDict = dict;
                                if (window.__agyBidiCfg && window.__agyBidiCfg.enablePersianUi) {
                                    applyAll();
                                }
                            }
                        })
                        .catch(function() {
                            st.faDictLoading = false;
                        });
                }

                function translateDurationFa(dur) {
                    return String(dur || '')
                        .replace(/\b(\d+)\s*days?\b/gi, '$1 روز')
                        .replace(/\b(\d+)\s*hours?\b/gi, '$1 ساعت')
                        .replace(/\b(\d+)\s*minutes?\b/gi, '$1 دقیقه')
                        .replace(/\b(\d+)\s*seconds?\b/gi, '$1 ثانیه')
                        .replace(/\band\b/gi, 'و')
                        .replace(/,\s*/g, '، ');
                }

                function lookupPersian(norm) {
                    if (!norm) return null;
                    var dict = st.faDict;
                    if (dict && Object.prototype.hasOwnProperty.call(dict, norm)) {
                        return dict[norm];
                    }
                    var m;
                    if ((m = norm.match(/^See all\s*\((\d+)\)$/i))) {
                        return 'مشاهده همه (' + m[1] + ')';
                    }
                    if ((m = norm.match(/^Show all\s*\((\d+)\)$/i))) {
                        return 'نمایش همه (' + m[1] + ')';
                    }
                    if ((m = norm.match(/^Show more\s*\((\d+)\)$/i))) {
                        return 'نمایش بیشتر (' + m[1] + ')';
                    }
                    if ((m = norm.match(/^Show submitted\s*\((\d+)\)$/i))) {
                        return 'نمایش ارسال‌شده‌ها (' + m[1] + ')';
                    }
                    if ((m = norm.match(/^Select Sidebar Item\s+(\d+)$/i))) {
                        return 'انتخاب مورد ' + m[1] + ' نوار کناری';
                    }
                    if ((m = norm.match(/^(\d+)\s+conversations?$/i))) {
                        return m[1] + ' گفت‌وگو';
                    }
                    if ((m = norm.match(/^(\d+)\s+pinned$/i))) {
                        return m[1] + ' سنجاق‌شده';
                    }
                    if ((m = norm.match(/^(\d+)\s+files?$/i))) {
                        return m[1] + ' فایل';
                    }
                    if ((m = norm.match(/^(\d+)\s+changes?$/i))) {
                        return m[1] + ' تغییر';
                    }
                    if ((m = norm.match(/^(\d+)\s*(?:s|sec|seconds?)\s+ago$/i))) {
                        return m[1] + ' ثانیه پیش';
                    }
                    if ((m = norm.match(/^(\d+)\s*(?:m|min|minutes?)\s+ago$/i))) {
                        return m[1] + ' دقیقه پیش';
                    }
                    if ((m = norm.match(/^(\d+)\s*(?:h|hr|hours?)\s+ago$/i))) {
                        return m[1] + ' ساعت پیش';
                    }
                    if ((m = norm.match(/^(\d+)\s*(?:d|days?)\s+ago$/i))) {
                        return m[1] + ' روز پیش';
                    }
                    if ((m = norm.match(/^(\d+)\s*(?:w|wk|weeks?)\s+ago$/i))) {
                        return m[1] + ' هفته پیش';
                    }
                    if ((m = norm.match(/^Available AI credits:\s*(.+)$/i))) {
                        return 'اعتبار هوش مصنوعی در دسترس: ' + m[1];
                    }
                    if ((m = norm.match(/^Your plan:\s*(.+)$/i))) {
                        return 'طرح شما: ' + m[1];
                    }
                    if ((m = norm.match(/^You have used some of your weekly limit,\s*it will fully refresh in\s+(.+?)\.?$/i))) {
                        return 'بخشی از سهمیه هفتگی شما مصرف شده است و در ' + translateDurationFa(m[1]) + ' به‌طور کامل بازنشانی می‌شود.';
                    }
                    if ((m = norm.match(/^You have used some of your 5-hour limit,\s*it will fully refresh in\s+(.+?)\.?$/i))) {
                        return 'بخشی از سهمیه ۵ ساعته شما مصرف شده است و در ' + translateDurationFa(m[1]) + ' به‌طور کامل بازنشانی می‌شود.';
                    }
                    if ((m = norm.match(/^Choose\s+(.+?)'s\s+Voice$/i))) {
                        return 'انتخاب صدای ' + m[1];
                    }
                    if ((m = norm.match(/^Powered by Gemini Live\s*·\s*(.+)$/i))) {
                        return 'با قدرت Gemini Live · ' + m[1];
                    }
                    if ((m = norm.match(/^(Default|Auto|Turbo|Request Review)(?:\s+and\s+(Default|Auto|Turbo|Request Review))?\s+needs?\s+the\s+terminal\s+sandbox,\s+which\s+isn't\s+available\s+on\s+this\s+host\.$/i))) {
                        var mapPreset = { 'default': 'پیش‌فرض', 'auto': 'خودکار', 'turbo': 'توربو', 'request review': 'درخواست بررسی' };
                        var p1 = mapPreset[m[1].toLowerCase()] || m[1];
                        if (m[2]) {
                            var p2 = mapPreset[m[2].toLowerCase()] || m[2];
                            return 'حالت‌های ' + p1 + ' و ' + p2 + ' نیازمند سندباکس ترمینال هستند که در این میزبان در دسترس نیست.';
                        }
                        return 'حالت ' + p1 + ' نیازمند سندباکس ترمینال است که در این میزبان در دسترس نیست.';
                    }
                    if ((m = norm.match(/^When toggled on,\s+(.+?)\s+will use your AI credits to fulfill model requests once you're out of model quota\./i))) {
                        return 'در صورت فعال بودن، پس از پایان سهمیه مدل از اعتبار هوش مصنوعی برای پاسخگویی استفاده می‌شود. همیشه ابتدا سهمیه مدل مصرف می‌شود.';
                    }
                    var MODEL_NAME_MAP = {
                        'gemini 4 hydrogen': 'جمینای ۴ هیدروژن',
                        'gemini 3.8 flash': 'جمینای ۳.۸ فلش',
                        'gemini 4 carbon': 'جمینای ۴ کربن',
                        'gemini 4 argon': 'جمینای ۴ آرگون',
                        'gemini 4 neon': 'جمینای ۴ نئون',
                        'claude sonnet 4.6': 'کلود سونت ۴.۶',
                        'claude opus 4.6': 'کلود اوپوس ۴.۶',
                        'gpt-oss 120b': 'جی‌پی‌تی اوپن‌سورس ۱۲۰B'
                    };
                    var MODEL_TAG_MAP = {
                        'thinking': 'استدلالی',
                        'low': 'کم',
                        'medium': 'متوسط',
                        'high': 'زیاد',
                        'extra high': 'خیلی زیاد',
                        'very high': 'خیلی زیاد',
                        'xhigh': 'خیلی زیاد',
                        'extra': 'بسیار زیاد',
                        'max': 'حداکثر'
                    };
                    if ((m = norm.match(/^(Gemini 4 Hydrogen|Gemini 3\.8 Flash|Gemini 4 Carbon|Gemini 4 Argon|Gemini 4 Neon|Claude Sonnet 4\.6|Claude Opus 4\.6|GPT-OSS 120B)(?:\s*\((Thinking|Low|Medium|High|Extra High|Very High|XHigh|Extra|Max)\))?$/i))) {
                        var mName = MODEL_NAME_MAP[m[1].toLowerCase()] || m[1];
                        if (m[2]) {
                            var mTag = MODEL_TAG_MAP[m[2].toLowerCase()] || m[2];
                            return mName + ' (' + mTag + ')';
                        }
                        return mName;
                    }
                    if ((m = norm.match(/^Yes,\s*and always allow\s+'(.+?)'\s+in this conversation$/i))) {
                        return 'بله، و در این گفت‌وگو همیشه به «' + m[1] + '» اجازه بده';
                    }
                    if ((m = norm.match(/^Yes,\s*and always allow\s+'(.+?)'$/i))) {
                        return 'بله، و همیشه به «' + m[1] + '» اجازه بده';
                    }
                    if ((m = norm.match(/^Yes,\s*allow\s+'?(.+?)'?\s+in this conversation$/i))) {
                        return 'بله، در این گفت‌وگو به «' + m[1] + '» اجازه بده';
                    }
                    if ((m = norm.match(/^Yes,\s*always allow\s+(.+?)$/i))) {
                        return 'بله، همیشه به «' + m[1] + '» اجازه بده';
                    }
                    if ((m = norm.match(/^Yes,\s*save rule for\s+'(.+?)'\s+in this conversation$/i))) {
                        return 'بله، ذخیره قانون برای «' + m[1] + '» در این گفت‌وگو';
                    }
                    if ((m = norm.match(/^Yes,\s*save rule for\s+'(.+?)'\s+globally$/i))) {
                        return 'بله، ذخیره قانون سراسری برای «' + m[1] + '»';
                    }
                    if ((m = norm.match(/^Allow\s+(.+?)\?$/i))) {
                        return 'اجازه به ' + m[1] + '؟';
                    }
                    if ((m = norm.match(/^Save rule to always allow\s+(.+?)\?$/i))) {
                        return 'ذخیره قانون برای اجازه همیشگی به ' + m[1] + '؟';
                    }
                    return null;
                }

                function buildStyleCss(cfg) {
                    if (!cfg.enableBidi && !cfg.enablePersianUi) return '';
                    var css = [];
                    css.push(
                        '@font-face {' +
                        ' font-family: "AgyVazirmatn";' +
                        ' src: url(' + $qFontUrl + ') format("woff2 supports variations"), url(' + $qFontUrl + ') format("woff2");' +
                        ' font-weight: 100 900;' +
                        ' font-style: normal;' +
                        ' font-display: swap;' +
                        ' unicode-range: U+0600-06FF, U+0750-077F, U+08A0-08FF, U+FB50-FDFF, U+FE70-FEFF;' +
                        '}'
                    );
                    css.push(
                        'body, .font-sans, p, li, h1, h2, h3, h4, h5, h6, blockquote, textarea, [contenteditable="true"] {' +
                        ' font-family: "AgyVazirmatn", var(--font-sans, "Google Sans Flex", "Inter", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif);' +
                        '}'
                    );
                    if (cfg.enableBidi) {
                        css.push(
                            'p, li, h1, h2, h3, h4, h5, h6, blockquote, textarea, [contenteditable="true"], [data-agy-bidi="1"]:not(pre):not(code):not(kbd):not(samp) {' +
                            ' unicode-bidi: plaintext;' +
                            '}'
                        );
                        css.push(
                            'pre, code, kbd, samp, .monaco-editor, .xterm, ' +
                            '[class*="diff-"], [class*="-diff"], [class*="diffEditor"], [class*="DiffEditor"], [class*="bg-diffEditor"], ' +
                            '[class*="terminal-"], [class*="-terminal"], .terminal, [data-testid*="terminal"], [data-testid*="diff"] {' +
                            ' direction: ltr !important;' +
                            ' text-align: left !important;' +
                            ' unicode-bidi: isolate !important;' +
                            '}'
                        );
                    }
                    if (cfg.enablePersianUi) {
                        css.push(
                            '[data-agy-fa-rtl="1"] {' +
                            ' direction: rtl;' +
                            ' text-align: start;' +
                            '}'
                        );
                        css.push(
                            'kbd, [data-agy-kbd="1"], [class*="shortcut-badge"], [class*="keybind"], [class*="accelerator"] {' +
                            ' direction: ltr !important;' +
                            ' text-align: left !important;' +
                            ' unicode-bidi: isolate !important;' +
                            '}'
                        );
                    }
                    return css.join('\n');
                }

                function syncStyle(cfg) {
                    var existing = document.getElementById(STYLE_ID);
                    var css = buildStyleCss(cfg);
                    if (!css) {
                        if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
                        return;
                    }
                    if (!existing) {
                        existing = document.createElement('style');
                        existing.id = STYLE_ID;
                        (document.head || document.documentElement).appendChild(existing);
                    }
                    if (existing.textContent !== css) {
                        existing.textContent = css;
                    }
                }

                function applyBidiEl(cfg, el) {
                    if (!el || el.nodeType !== 1) return;
                    if (el.closest && el.closest(CODE_LOCK_SEL)) return;
                    if (!st.origDir.has(el)) {
                        st.origDir.set(el, el.hasAttribute('dir') ? el.getAttribute('dir') : null);
                    }
                    if (el.getAttribute('dir') !== 'auto') {
                        el.setAttribute('dir', 'auto');
                    }
                    if (el.getAttribute('data-agy-bidi') !== '1') {
                        el.setAttribute('data-agy-bidi', '1');
                    }
                }

                function applyBidi(cfg, root) {
                    if (!root || root.nodeType !== 1) return;
                    if (cfg.enableBidi) {
                        if (root.matches && root.matches(BIDI_TARGET_SEL)) {
                            applyBidiEl(cfg, root);
                        }
                        var nodes = root.querySelectorAll ? root.querySelectorAll(BIDI_TARGET_SEL) : [];
                        for (var i = 0; i < nodes.length; i++) {
                            applyBidiEl(cfg, nodes[i]);
                        }
                    } else {
                        var marked = root.querySelectorAll ? root.querySelectorAll('[data-agy-bidi="1"]') : [];
                        for (var j = 0; j < marked.length; j++) {
                            var m = marked[j];
                            if (st.origDir.has(m)) {
                                var orig = st.origDir.get(m);
                                if (orig === null) m.removeAttribute('dir');
                                else m.setAttribute('dir', orig);
                                st.origDir.delete(m);
                            } else if (m.getAttribute('dir') === 'auto') {
                                m.removeAttribute('dir');
                            }
                            m.removeAttribute('data-agy-bidi');
                        }
                    }
                }

                function isFastSkipProseNode(node) {
                    if (!node) return true;
                    var el = (node.nodeType === 3) ? node.parentElement : (node.nodeType === 1 ? node : null);
                    if (!el || !el.closest) return false;
                    if (el.closest(HARD_SKIP_SEL)) return true;
                    if (el.closest(CHAT_PROSE_SEL)) {
                        return !el.closest(CHROME_CONTAINER_SEL);
                    }
                    return false;
                }

                function isExcludedChrome(el) {
                    if (!el || el.nodeType !== 1) return true;
                    if (el.closest && el.closest(HARD_SKIP_SEL)) return true;
                    if (el.closest && el.closest(CHAT_PROSE_SEL)) {
                        return !(el.closest(CHROME_CONTAINER_SEL));
                    }
                    return false;
                }

                function markChromeDir(el, dirVal, markerAttr) {
                    if (!el || el.nodeType !== 1) return;
                    if (!st.origChromeDir.has(el)) {
                        st.origChromeDir.set(el, el.hasAttribute('dir') ? el.getAttribute('dir') : null);
                    }
                    if (el.getAttribute('dir') !== dirVal) {
                        el.setAttribute('dir', dirVal);
                    }
                    if (el.getAttribute(markerAttr) !== '1') {
                        el.setAttribute(markerAttr, '1');
                    }
                }

                function restoreChromeDirs(root) {
                    if (!root || !root.querySelectorAll) return;
                    var marked = root.querySelectorAll('[data-agy-fa-rtl="1"], [data-agy-kbd="1"]');
                    for (var i = 0; i < marked.length; i++) {
                        var el = marked[i];
                        if (st.origChromeDir.has(el)) {
                            var orig = st.origChromeDir.get(el);
                            if (orig === null) el.removeAttribute('dir');
                            else el.setAttribute('dir', orig);
                            st.origChromeDir.delete(el);
                        } else {
                            el.removeAttribute('dir');
                        }
                        el.removeAttribute('data-agy-fa-rtl');
                        el.removeAttribute('data-agy-kbd');
                    }
                }

                function applyPanelRtl(root) {
                    if (!root || root.nodeType !== 1) return;
                    if (root.matches && root.matches(PANEL_RTL_SEL) && !(root.closest && root.closest(HARD_SKIP_SEL))) {
                        markChromeDir(root, 'rtl', 'data-agy-fa-rtl');
                    }
                    var panels = root.querySelectorAll ? root.querySelectorAll(PANEL_RTL_SEL) : [];
                    for (var i = 0; i < panels.length; i++) {
                        var p = panels[i];
                        if (p.closest && p.closest(HARD_SKIP_SEL)) continue;
                        markChromeDir(p, 'rtl', 'data-agy-fa-rtl');
                    }
                }

                function lockLtrValues(root) {
                    if (!root || root.nodeType !== 1) return;
                    var checkEl = function(el) {
                        if (!el || el.nodeType !== 1) return;
                        if (isFastSkipProseNode(el)) return;
                        if (el.tagName === 'KBD' || (el.className && typeof el.className === 'string' && /shortcut|keybind|accelerator/i.test(el.className))) {
                            if (el.childElementCount === 0 || el.tagName === 'KBD') {
                                markChromeDir(el, 'ltr', 'data-agy-kbd');
                            }
                            return;
                        }
                        if (el.childElementCount === 0) {
                            var t = (el.textContent || '').trim();
                            if (t && t.length <= 36 && LTR_VALUE_RE.test(t)) {
                                markChromeDir(el, 'ltr', 'data-agy-kbd');
                            }
                        }
                    };
                    checkEl(root);
                    var candidates = root.querySelectorAll ? root.querySelectorAll('kbd, [class*="shortcut"], [class*="keybind"], [class*="accelerator"], span, code, div') : [];
                    for (var i = 0; i < candidates.length; i++) {
                        checkEl(candidates[i]);
                    }
                }

                function lookupInlineSplitFa(raw, parent) {
                    var full = (parent && parent.textContent) ? parent.textContent.replace(/\s+/g, ' ').trim() : '';
                    if (raw === "Yes, and always allow '") {
                        if (/'\s+in this conversation$/i.test(full)) {
                            return 'بله، و در این گفت‌وگو همیشه به «';
                        }
                        return 'بله، و همیشه به «';
                    }
                    if (raw === "' in this conversation") {
                        return '» اجازه بده';
                    }
                    if (raw === "'" && /^Yes,\s*(?:and\s+)?always allow\s+'/i.test(full)) {
                        return '» اجازه بده';
                    }
                    if (raw === "Yes, save rule for '") {
                        if (/'\s+in this conversation$/i.test(full)) {
                            return 'بله، ذخیره قانون برای «';
                        }
                        return 'بله، ذخیره قانون سراسری برای «';
                    }
                    if (raw === "' globally" && /^Yes,\s*save rule for\s+'/i.test(full)) {
                        return '»';
                    }
                    if (raw === "Yes, allow downloads from ") {
                        return 'بله، اجازه دانلود از ';
                    }
                    if (raw === "Yes, always allow downloads from ") {
                        return 'بله، اجازه همیشگی دانلود از ';
                    }
                    if (raw === "Yes, allow ") {
                        return 'بله، اجازه به ';
                    }
                    if (raw === "Yes, always allow ") {
                        return 'بله، اجازه همیشگی به ';
                    }
                    if (raw === " in this conversation" && /^Yes,/i.test(full)) {
                        return ' در این گفت‌وگو';
                    }
                    return null;
                }

                function translateSingleTextNode(node, translate) {
                    if (!node || node.nodeType !== 3) return;
                    var parent = node.parentElement;
                    if (!parent) return;
                    if (translate) {
                        var tag = parent.tagName;
                        if (tag === 'TEXTAREA' || tag === 'INPUT' || isExcludedChrome(parent)) return;
                        if (parent.closest && parent.closest('[contenteditable="true"]')) return;
                        var raw = node.nodeValue;
                        if (!raw) return;
                        var splitFa = lookupInlineSplitFa(raw, parent);
                        if (splitFa) {
                            var sRec = st.origText.get(node);
                            if (!sRec || raw !== sRec.translated) {
                                st.origText.set(node, { orig: raw, translated: splitFa });
                            }
                            if (node.nodeValue !== splitFa) {
                                node.nodeValue = splitFa;
                            }
                            var sRtlHost = parent.closest('h1, h2, h3, h4, h5, h6, p, label, button, a, summary, [role="menuitem"], [role="option"], [role="tab"], [class*="settings-row"]') || parent;
                            if (!sRtlHost.closest('[data-agy-kbd="1"]')) {
                                markChromeDir(sRtlHost, 'rtl', 'data-agy-fa-rtl');
                            }
                            return;
                        }
                        var norm = raw.replace(/\s+/g, ' ').trim();
                        if (!norm) return;
                        if (LTR_VALUE_RE.test(norm)) {
                            markChromeDir(parent, 'ltr', 'data-agy-kbd');
                            return;
                        }
                        var fa = lookupPersian(norm);
                        if (!fa) return;
                        var lead = raw.match(/^\s*/)[0];
                        var trail = raw.match(/\s*$/)[0];
                        var nextVal = lead + fa + trail;
                        var rec = st.origText.get(node);
                        if (!rec || raw !== rec.translated) {
                            st.origText.set(node, { orig: raw, translated: nextVal });
                        }
                        if (node.nodeValue !== nextVal) {
                            node.nodeValue = nextVal;
                        }
                        var rtlHost = parent.closest('h1, h2, h3, h4, h5, h6, p, label, button, a, summary, [role="menuitem"], [role="option"], [role="tab"], [class*="settings-row"]') || parent;
                        if (!rtlHost.closest('[data-agy-kbd="1"]')) {
                            markChromeDir(rtlHost, 'rtl', 'data-agy-fa-rtl');
                        }
                    } else {
                        var saved = st.origText.get(node);
                        if (saved) {
                            if (node.nodeValue === saved.translated) {
                                node.nodeValue = saved.orig;
                            }
                            st.origText.delete(node);
                        }
                    }
                }

                function translateTextNodes(root, translate) {
                    if (!root) return;
                    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
                    var node;
                    while ((node = walker.nextNode())) {
                        translateSingleTextNode(node, translate);
                    }
                }

                function translateElementAttributes(el, translate) {
                    if (!el || el.nodeType !== 1) return;
                    var attrs = ['placeholder', 'aria-label', 'title'];
                    if (translate) {
                        if (isExcludedChrome(el)) return;
                        for (var a = 0; a < attrs.length; a++) {
                            var attr = attrs[a];
                            var val = el.getAttribute(attr);
                            if (!val) continue;
                            var norm = val.replace(/\s+/g, ' ').trim();
                            var fa = lookupPersian(norm);
                            if (!fa) continue;
                            var map = st.origAttr.get(el) || {};
                            var prev = map[attr];
                            if (!prev || val !== prev.translated) {
                                map[attr] = { orig: val, translated: fa };
                                st.origAttr.set(el, map);
                            }
                            if (val !== fa) {
                                el.setAttribute(attr, fa);
                            }
                            if (attr === 'placeholder' && el.tagName !== 'TEXTAREA' && !(el.closest && el.closest('[contenteditable="true"]'))) {
                                markChromeDir(el, 'rtl', 'data-agy-fa-rtl');
                            }
                        }
                    } else {
                        var savedMap = st.origAttr.get(el);
                        if (savedMap) {
                            for (var k = 0; k < attrs.length; k++) {
                                var key = attrs[k];
                                if (savedMap[key] && el.getAttribute(key) === savedMap[key].translated) {
                                    el.setAttribute(key, savedMap[key].orig);
                                }
                            }
                            st.origAttr.delete(el);
                        }
                    }
                }

                function translateAttributes(root, translate) {
                    if (!root || !root.querySelectorAll) return;
                    translateElementAttributes(root, translate);
                    var els = root.querySelectorAll('[placeholder], [aria-label], [title]');
                    for (var i = 0; i < els.length; i++) {
                        translateElementAttributes(els[i], translate);
                    }
                }

                function applyAll() {
                    var cfg = window.__agyBidiCfg || { enableBidi: true, enablePersianUi: false };
                    if (cfg.enablePersianUi && !st.faDict) {
                        ensureFaDictLoaded();
                    }
                    if (st.applying) return;
                    st.applying = true;
                    try {
                        syncStyle(cfg);
                        if (document.body) {
                            applyBidi(cfg, document.body);
                            if (cfg.enablePersianUi) {
                                applyPanelRtl(document.body);
                            }
                            translateTextNodes(document.body, !!cfg.enablePersianUi);
                            translateAttributes(document.body, !!cfg.enablePersianUi);
                            if (cfg.enablePersianUi) {
                                lockLtrValues(document.body);
                            } else {
                                restoreChromeDirs(document.body);
                            }
                        }
                    } catch (e) {} finally {
                        if (st.observer && typeof st.observer.takeRecords === 'function') {
                            try { st.observer.takeRecords(); } catch (e) {}
                        }
                        st.applying = false;
                    }
                }

                function handleMutations(mutations) {
                    if (st.applying) return;
                    var cfg = window.__agyBidiCfg || { enableBidi: true, enablePersianUi: false };
                    if (!cfg.enableBidi && !cfg.enablePersianUi) return;
                    if (cfg.enablePersianUi && !st.faDict) {
                        ensureFaDictLoaded();
                    }
                    st.applying = true;
                    try {
                        if (!document.getElementById(STYLE_ID)) {
                            syncStyle(cfg);
                        }
                        var addedEls = new Set();
                        var textNodes = cfg.enablePersianUi ? new Set() : null;
                        var attrEls = cfg.enablePersianUi ? new Set() : null;

                        for (var i = 0; i < mutations.length; i++) {
                            var m = mutations[i];
                            if (m.type === 'characterData') {
                                if (textNodes && m.target && !isFastSkipProseNode(m.target)) {
                                    textNodes.add(m.target);
                                }
                            } else if (m.type === 'attributes') {
                                if (attrEls && m.target && m.target.nodeType === 1 && !isFastSkipProseNode(m.target)) {
                                    attrEls.add(m.target);
                                }
                            } else if (m.type === 'childList' && m.addedNodes) {
                                for (var j = 0; j < m.addedNodes.length; j++) {
                                    var node = m.addedNodes[j];
                                    if (node.nodeType === 3) {
                                        if (textNodes && !isFastSkipProseNode(node)) {
                                            textNodes.add(node);
                                        }
                                    } else if (node.nodeType === 1) {
                                        addedEls.add(node);
                                    }
                                }
                            }
                        }

                        if (textNodes) {
                            textNodes.forEach(function(tn) {
                                if (tn.isConnected !== false) {
                                    translateSingleTextNode(tn, true);
                                }
                            });
                        }
                        if (attrEls) {
                            attrEls.forEach(function(ae) {
                                if (ae.isConnected !== false) {
                                    translateElementAttributes(ae, true);
                                }
                            });
                        }

                        // Deduplicate added subtrees per batch (skip elements whose ancestor is also in this batch)
                        var dedupedRoots = [];
                        addedEls.forEach(function(el) {
                            if (el.isConnected === false) return;
                            var p = el.parentElement;
                            while (p) {
                                if (addedEls.has(p)) return;
                                p = p.parentElement;
                            }
                            dedupedRoots.push(el);
                        });

                        for (var k = 0; k < dedupedRoots.length; k++) {
                            var rootNode = dedupedRoots[k];
                            if (isFastSkipProseNode(rootNode)) {
                                if (cfg.enableBidi) {
                                    applyBidi(cfg, rootNode);
                                }
                            } else {
                                if (cfg.enableBidi) {
                                    applyBidi(cfg, rootNode);
                                }
                                if (cfg.enablePersianUi) {
                                    applyPanelRtl(rootNode);
                                    translateTextNodes(rootNode, true);
                                    translateAttributes(rootNode, true);
                                    lockLtrValues(rootNode);
                                }
                            }
                        }
                    } catch (e) {} finally {
                        if (st.observer && typeof st.observer.takeRecords === 'function') {
                            try { st.observer.takeRecords(); } catch (e) {}
                        }
                        st.applying = false;
                    }
                }

                function ensureObserver() {
                    if (st.observerInstalled) return;
                    var observeRoot = document.documentElement || document.body;
                    if (!observeRoot) return;
                    st.observerInstalled = true;
                    st.observer = new MutationObserver(handleMutations);
                    st.observer.observe(observeRoot, {
                        childList: true,
                        subtree: true,
                        characterData: true,
                        attributes: true,
                        attributeFilter: ['placeholder', 'aria-label', 'title']
                    });
                }

                applyAll();
                ensureObserver();
            })();
        """.trimIndent()
    }
}
