package com.termux.app

import org.json.JSONObject

/**
 * JavaScript snippets injected into the trusted Antigravity Hub WebView
 * for agent-completion notifications, quota exhaustion detection, and blob/data downloads.
 */
object AntigravityJsBridge {

    val AGENT_BRIDGE_JS: String = """
        (function() {
            if (window.__agyNotifBridgeInstalled) return;
            window.__agyNotifBridgeInstalled = true;

            try {
                function AgyNotification(title, options) {
                    var body = (options && options.body) ? String(options.body) : '';
                    if (window.AntigravityAndroid) {
                        window.AntigravityAndroid.notify(String(title || 'Antigravity'), body);
                    }
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
                                if (Date.now() - lastNotifAt > 5000 && window.AntigravityAndroid) {
                                    lastNotifAt = Date.now();
                                    window.AntigravityAndroid.onAgentJobFinished('Agent finished its task');
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

            // Quota exhaustion detection (fetch, XHR, and visible error banners)
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
                if (window.AntigravityAndroid && typeof window.AntigravityAndroid.onQuotaExhausted === 'function') {
                    window.AntigravityAndroid.onQuotaExhausted(String(reason || 'quota_exhausted').slice(0, 240));
                }
            }

            if (window.fetch && !window.__agyFetchWrapped) {
                window.__agyFetchWrapped = true;
                var origFetch = window.fetch;
                window.fetch = function() {
                    return origFetch.apply(this, arguments).then(function(resp) {
                        try {
                            if (resp && resp.status === 429) {
                                resp.clone().text().then(function(body) {
                                    if (!body || isQuotaText(body)) {
                                        signalQuota(body || 'HTTP 429 rate limit');
                                    }
                                }).catch(function() {
                                    signalQuota('HTTP 429 rate limit');
                                });
                            } else if (resp && !resp.ok && resp.status >= 400) {
                                resp.clone().text().then(function(body) {
                                    if (isQuotaText(body)) signalQuota(body);
                                }).catch(function() {});
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
                    this.addEventListener('load', function() {
                        try {
                            if (this.status === 429) {
                                var txt429 = (typeof this.responseText === 'string') ? this.responseText : '';
                                if (!txt429 || isQuotaText(txt429)) {
                                    signalQuota(txt429 || 'XHR 429 rate limit');
                                }
                            } else if (this.status >= 400 && typeof this.responseText === 'string' && isQuotaText(this.responseText)) {
                                signalQuota(this.responseText);
                            }
                        } catch (e) {}
                    });
                    return origOpen.apply(this, arguments);
                };
            }

            setInterval(function() {
                try {
                    var alerts = document.querySelectorAll('[role="alert"], [class*="error"], [class*="quota"], [class*="banner"], [class*="toast"]');
                    for (var i = 0; i < alerts.length; i++) {
                        var el = alerts[i];
                        if (el && el.offsetParent !== null) {
                            var txt = (el.textContent || '').trim();
                            if (txt.length > 0 && txt.length < 600 && isQuotaText(txt)) {
                                signalQuota(txt);
                                break;
                            }
                        }
                    }
                } catch (e) {}
            }, 3000);
        })();
    """.trimIndent()

    fun buildBlobDownloadJs(url: String, guessedName: String): String {
        val qUrl = JSONObject.quote(url)
        val qName = JSONObject.quote(guessedName)
        return """
            (function() {
                fetch($qUrl).then(function(r){ return r.blob(); }).then(function(b){
                    if (b.size > 25 * 1024 * 1024) {
                        if (window.AntigravityAndroid) {
                            window.AntigravityAndroid.notifyDownloadTooLarge();
                        }
                        return;
                    }
                    var fr = new FileReader();
                    fr.onloadend = function() {
                        if (window.AntigravityAndroid && fr.result) {
                            window.AntigravityAndroid.saveDownloadedBase64($qName, String(fr.result));
                        }
                    };
                    fr.readAsDataURL(b);
                }).catch(function(){});
            })();
        """.trimIndent()
    }
}
