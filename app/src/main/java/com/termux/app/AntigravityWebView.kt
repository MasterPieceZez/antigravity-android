package com.termux.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat

/**
 * WebView subclass that advertises ViewCompat OnReceiveContent image MIME types
 * on its InputConnection EditorInfo and dispatches IME commitContent safely on the UI thread
 * so Android keyboards (Gboard, Samsung Keyboard) can paste images without WebView thread crashes.
 */
class AntigravityWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    var onImeImageCommitted: ((uri: Uri, mimeType: String?, releasePermission: () -> Unit) -> Unit)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        val mimeTypes = ViewCompat.getOnReceiveContentMimeTypes(this)
            ?: arrayOf("image/*", "image/png", "image/jpeg", "image/webp", "image/gif")
        EditorInfoCompat.setContentMimeTypes(outAttrs, mimeTypes)
        val callback = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
            ) {
                try {
                    inputContentInfo.requestPermission()
                } catch (_: Throwable) {
                    return@OnCommitContentListener false
                }
            }
            val contentUri = inputContentInfo.contentUri
            val desc = inputContentInfo.description
            val mime = if (desc.mimeTypeCount > 0) desc.getMimeType(0) else null
            val releaseFn: () -> Unit = {
                runCatching { inputContentInfo.releasePermission() }
            }
            val listener = onImeImageCommitted
            if (listener != null) {
                post {
                    listener(contentUri, mime, releaseFn)
                }
                true
            } else {
                post {
                    val clip = ClipData(
                        desc ?: ClipDescription("image", arrayOf(mime ?: "image/*")),
                        ClipData.Item(contentUri)
                    )
                    val info = ContentInfoCompat.Builder(clip, ContentInfoCompat.SOURCE_INPUT_METHOD).build()
                    ViewCompat.performReceiveContent(this@AntigravityWebView, info)
                    releaseFn()
                }
                true
            }
        }
        return InputConnectionCompat.createWrapper(ic, outAttrs, callback)
    }
}
