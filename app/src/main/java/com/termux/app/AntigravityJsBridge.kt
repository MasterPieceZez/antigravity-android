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
                    var cardEl = document.querySelector('[class*="card"], [class*="panel"], header, nav, aside, button');
                    var cardCs = cardEl ? getComputedStyle(cardEl) : bodyCs;
                    var cardBg = isOpaqueColor(cardCs.backgroundColor) ? cardCs.backgroundColor : surfaceBg;
                    var borderColor = isOpaqueColor(cardCs.borderColor) ? cardCs.borderColor : (isOpaqueColor(cardCs.borderTopColor) ? cardCs.borderTopColor : '');
                    var borderW = parseFloat(cardCs.borderTopWidth || cardCs.borderWidth || '1') || 1;
                    var radius = parseFloat(cardCs.borderRadius || '18') || 18;
                    var hasShadow = (cardCs.boxShadow && cardCs.boxShadow !== 'none');
                    var btnEl = document.querySelector('button[class*="primary"], a[class*="primary"], button');
                    var btnCs = btnEl ? getComputedStyle(btnEl) : cardCs;
                    var accentColor = isOpaqueColor(btnCs.backgroundColor) ? btnCs.backgroundColor : '';
                    var textPrimary = bodyCs.color || '';
                    var mutedEl = document.querySelector('[class*="muted"], [class*="secondary"], small');
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

    /**
     * Builds the idempotent, live-toggleable JavaScript for Smart BiDi/RTL layout,
     * offline Vazirmatn font loading, and React-safe Persian UI chrome translation.
     */
    fun buildPersianBidiJs(enableBidi: Boolean, enablePersianUi: Boolean): String {
        val qFontUrl = quoteJsString(VAZIRMATN_ASSET_URL)
        val qDictUrl = quoteJsString(FA_DICT_ASSET_URL)
        return """
            (function() {
                var enableBidi = $enableBidi;
                var enablePersianUi = $enablePersianUi;
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
                    timer: null,
                    applying: false
                });

                var STYLE_ID = 'agy-bidi-style';
                var BIDI_TARGET_SEL = 'p, li, h1, h2, h3, h4, h5, h6, blockquote, textarea, [contenteditable="true"], [class*="prose"], [class*="markdown"], [data-testid*="message"], [class*="message-content"], [class*="message-body"], [class*="chat-message"]';
                var CODE_LOCK_SEL = 'pre, code, kbd, samp, .monaco-editor, .xterm, [class*="diff-"], [class*="-diff"], [class*="diffEditor"], [class*="DiffEditor"], [class*="bg-diffEditor"], [class*="terminal-"], [class*="-terminal"], .terminal, [data-testid*="terminal"], [data-testid*="diff"]';
                var HARD_SKIP_SEL = 'pre, code, kbd, samp, script, style, noscript, svg, .monaco-editor, .xterm, [class*="diff-"], [class*="-diff"], [class*="diffEditor"], [class*="DiffEditor"], [class*="bg-diffEditor"], [class*="terminal-"], [class*="-terminal"], .terminal, [data-testid*="terminal"], [data-testid*="diff"]';
                var CHAT_PROSE_SEL = '[data-testid="live-user-turn"], [data-testid="live-agent-turn"], [data-testid*="message-content"], [class*="message-content"], [class*="message-body"], .prose, [class*="prose "], [class*=" prose"], [class*="markdown"]';
                var CHROME_CONTAINER_SEL = '[role="dialog"], [role="menu"], nav, aside, header, [class*="settings"], [data-testid*="settings"], [class*="sidebar"], [class*="toolbar"]';
                var KBD_BADGE_RE = /^(?:Ctrl|Cmd|Alt|Shift|Meta|CmdOrCtrl|Opt|⌘|⌥|⌃|⇧)(?:\s*\+\s*[^\s]+)+$/i;

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

                function applyBidi(cfg, root) {
                    if (!root) return;
                    if (cfg.enableBidi) {
                        var nodes = root.querySelectorAll ? root.querySelectorAll(BIDI_TARGET_SEL) : [];
                        for (var i = 0; i < nodes.length; i++) {
                            var el = nodes[i];
                            if (el.closest && el.closest(CODE_LOCK_SEL)) continue;
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

                function lockShortcutBadges(root) {
                    if (!root || !root.querySelectorAll) return;
                    var candidates = root.querySelectorAll('kbd, [class*="shortcut"], [class*="keybind"], [class*="accelerator"], span, code');
                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        if (el.tagName === 'KBD' || (el.className && typeof el.className === 'string' && /shortcut|keybind|accelerator/i.test(el.className))) {
                            if (el.childElementCount === 0 || el.tagName === 'KBD') {
                                markChromeDir(el, 'ltr', 'data-agy-kbd');
                            }
                            continue;
                        }
                        if (el.childElementCount === 0) {
                            var t = (el.textContent || '').trim();
                            if (t && t.length <= 32 && KBD_BADGE_RE.test(t)) {
                                markChromeDir(el, 'ltr', 'data-agy-kbd');
                            }
                        }
                    }
                }

                function translateTextNodes(root, translate) {
                    if (!root) return;
                    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
                    var node;
                    while ((node = walker.nextNode())) {
                        var parent = node.parentElement;
                        if (!parent) continue;
                        if (translate) {
                            var tag = parent.tagName;
                            if (tag === 'TEXTAREA' || tag === 'INPUT' || isExcludedChrome(parent)) continue;
                            if (parent.closest && parent.closest('[contenteditable="true"]')) continue;
                            var raw = node.nodeValue;
                            if (!raw) continue;
                            var norm = raw.replace(/\s+/g, ' ').trim();
                            if (!norm) continue;
                            if (KBD_BADGE_RE.test(norm)) {
                                markChromeDir(parent, 'ltr', 'data-agy-kbd');
                                continue;
                            }
                            var fa = lookupPersian(norm);
                            if (!fa) continue;
                            var leadMatch = raw.match(/^\s*/);
                            var trailMatch = raw.match(/\s*$/);
                            var lead = leadMatch ? leadMatch[0] : '';
                            var trail = (trailMatch && lead.length < raw.length) ? trailMatch[0] : '';
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
                }

                function translateAttributes(root, translate) {
                    if (!root || !root.querySelectorAll) return;
                    var attrs = ['placeholder', 'aria-label', 'title'];
                    var els = root.querySelectorAll('[placeholder], [aria-label], [title]');
                    for (var i = 0; i < els.length; i++) {
                        var el = els[i];
                        if (translate) {
                            if (isExcludedChrome(el)) continue;
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
                            translateTextNodes(document.body, !!cfg.enablePersianUi);
                            translateAttributes(document.body, !!cfg.enablePersianUi);
                            if (cfg.enablePersianUi) {
                                lockShortcutBadges(document.body);
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

                function scheduleApply() {
                    if (st.applying) return;
                    if (st.timer) clearTimeout(st.timer);
                    st.timer = setTimeout(function() {
                        st.timer = null;
                        applyAll();
                    }, 80);
                }

                function ensureObserver() {
                    if (st.observerInstalled) return;
                    if (!document.body) {
                        setTimeout(ensureObserver, 150);
                        return;
                    }
                    st.observerInstalled = true;
                    st.observer = new MutationObserver(scheduleApply);
                    st.observer.observe(document.body, {
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
