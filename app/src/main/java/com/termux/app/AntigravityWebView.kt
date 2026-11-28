package com.termux.app

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat

/**
 * WebView subclass that advertises ViewCompat OnReceiveContent image MIME types
 * on its InputConnection EditorInfo so Android keyboards (Gboard, Samsung Keyboard)
 * allow direct image/screenshot pasting from the keyboard clipboard.
 */
class AntigravityWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        val mimeTypes = ViewCompat.getOnReceiveContentMimeTypes(this)
        if (!mimeTypes.isNullOrEmpty()) {
            EditorInfoCompat.setContentMimeTypes(outAttrs, mimeTypes)
            return InputConnectionCompat.createWrapper(this, ic, outAttrs)
        }
        return ic
    }
}
