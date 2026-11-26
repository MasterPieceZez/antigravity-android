package com.termux.app

import org.json.JSONObject

/**
 * JavaScript snippets injected into the trusted Antigravity Hub WebView
 * for agent-completion notifications, quota exhaustion detection, and blob/data downloads.
 *
 * Prefers WebViewCompat's origin-scoped WebMessageListener (`window.AntigravityBridge`)
 * and falls back to `window.AntigravityAndroid` only on legacy WebView implementations.
 */
object AntigravityJsBridge {

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
                    if (label === 'stop' || label.indexOf('stop generating') !== -1 || label.indexOf('stop agent') !== -1 || label.indexOf('stop response') !== -1) {
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
                if (!checkScheduled) {
                    checkScheduled = true;
                    setTimeout(checkState, 300);
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
            setInterval(checkState, 2000);

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

            if (window.fetch && !window.__agyFetchWrapped) {
                window.__agyFetchWrapped = true;
                var origFetch = window.fetch;
                window.fetch = function() {
                    return origFetch.apply(this, arguments).then(function(resp) {
                        try {
                            if (resp && (resp.status === 429 || (!resp.ok && resp.status >= 400))) {
                                var cl = parseInt((resp.headers && resp.headers.get('content-length')) || '0', 10);
                                if (!cl || cl <= 65536) {
                                    resp.clone().text().then(function(body) {
                                        if (resp.status === 429) {
                                            if (!body || isQuotaText(body)) {
                                                signalQuota(body || 'HTTP 429 rate limit');
                                            }
                                        } else if (isQuotaText(body)) {
                                            signalQuota(body);
                                        }
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
                                } else if (this.status >= 400 && typeof this.responseText === 'string' && this.responseText.length <= 65536 && isQuotaText(this.responseText)) {
                                    signalQuota(this.responseText);
                                }
                            } catch (e) {}
                        });
                    }
                    return origOpen.apply(this, arguments);
                };
            }
        })();
    """.trimIndent()

    fun buildBlobDownloadJs(url: String, guessedName: String): String {
        val qUrl = JSONObject.quote(url)
        val qName = JSONObject.quote(guessedName)
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
}
