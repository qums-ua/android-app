package me.guptaishaan.quarp

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Extracts the captcha image from the WebView's #imgPhoto element,
 * runs ML Kit OCR on it, and fills the result into the #captcha input.
 *
 * Features retry with backoff (up to 3 attempts) and silent operation
 * (toasts only on error). Can be toggled via SharedPreferences.
 */
object CaptchaHelper {

    private const val TAG = "CaptchaHelper"
    private const val PREFS_NAME = "quarp_prefs"
    private const val KEY_AUTO_SOLVE = "auto_solve_captcha"
    private const val MAX_RETRIES = 3
    private val RETRY_DELAYS_MS = longArrayOf(500L, 1000L, 1500L)

    private val handler = Handler(Looper.getMainLooper())

    private val JS_CHECK_IMG = """
        (function() {
            var img = document.getElementById("imgPhoto");
            if (!img || !img.src) return "";
            var holder = document.getElementById("__captchaData__");
            if (!holder) {
                holder = document.createElement("div");
                holder.id = "__captchaData__";
                holder.style.display = "none";
                document.body.appendChild(holder);
            }
            holder.textContent = img.src;
            return "ok";
        })();
    """.trimIndent()

    private val JS_READ_IMG = """
        (function() {
            var el = document.getElementById("__captchaData__");
            return el ? el.textContent : "";
        })();
    """.trimIndent()

    private fun jsFillCaptcha(text: String): String = """
        (function() {
            var el = document.getElementById("captcha");
            if (!el) return false;
            el.value = "$text";
            el.dispatchEvent(new Event("input", { bubbles: true }));
            el.dispatchEvent(new Event("change", { bubbles: true }));
            return true;
        })();
    """.trimIndent()

    /** Returns true if auto-solve is enabled (default: true). */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SOLVE, true)
    }

    /** Sets the auto-solve preference. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SOLVE, enabled).apply()
    }

    /**
     * Main entry point. Call this after a page has loaded and you want to
     * auto-solve the captcha. Extracts the image, runs OCR, fills the input.
     * Silently retries up to [MAX_RETRIES] times with backoff.
     */
    fun extractAndSolveCaptcha(context: Context, webView: WebView) {
        attemptCaptchaSolve(context, webView, attempt = 0)
    }

    private fun attemptCaptchaSolve(context: Context, webView: WebView, attempt: Int) {
        // Step 1: Store the imgPhoto src in a hidden DOM element
        webView.evaluateJavascript(JS_CHECK_IMG) { checkResult ->
            // evaluateJavascript wraps string returns in quotes, so "ok" becomes "\"ok\""
            if (checkResult != "\"ok\"") {
                Log.d(TAG, "No #imgPhoto found on page \u2014 skipping")
                return@evaluateJavascript
            }

            // Step 2: Read the base64 data
            webView.evaluateJavascript(JS_READ_IMG) { base64Src ->
                if (base64Src.isNullOrBlank() || base64Src == "null") {
                    Log.d(TAG, "Captcha image data is empty")
                    return@evaluateJavascript
                }

                val bitmap = decodeBase64ToBitmap(base64Src)
                if (bitmap == null) {
                    Log.w(TAG, "Failed to decode captcha image")
                    Toast.makeText(context, "Captcha: failed to decode image", Toast.LENGTH_SHORT).show()
                    return@evaluateJavascript
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val rawText = visionText.text.trim()
                        val captchaText = rawText.replace(Regex("\\s+"), "").uppercase()

                        if (captchaText.isEmpty() && attempt < MAX_RETRIES - 1) {
                            val nextAttempt = attempt + 1
                            Log.d(TAG, "OCR returned empty (attempt $nextAttempt/$MAX_RETRIES), retrying...")
                            handler.postDelayed(
                                { attemptCaptchaSolve(context, webView, nextAttempt) },
                                RETRY_DELAYS_MS[attempt]
                            )
                            return@addOnSuccessListener
                        }

                        if (captchaText.isEmpty()) {
                            Log.w(TAG, "ML Kit returned empty after $MAX_RETRIES attempts")
                            return@addOnSuccessListener
                        }

                        Log.d(TAG, "Captcha OCR result: $captchaText")
                        webView.evaluateJavascript(jsFillCaptcha(captchaText)) { result ->
                            val filled = result?.toBooleanStrictOrNull() == true
                            if (!filled) {
                                Log.w(TAG, "Captcha input field (#captcha) not found in page")
                                Toast.makeText(context, "Captcha: input field not found", Toast.LENGTH_SHORT).show()
                            }
                            // Silent on success; only toast on failure
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "ML Kit text recognition failed", e)
                        Toast.makeText(context, "Captcha recognition failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    private fun decodeBase64ToBitmap(base64Src: String): android.graphics.Bitmap? {
        return try {
            val base64Data = if (base64Src.contains(",")) {
                base64Src.substringAfter(",")
            } else {
                base64Src
            }
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Base64 decode error", e)
            null
        }
    }
}
