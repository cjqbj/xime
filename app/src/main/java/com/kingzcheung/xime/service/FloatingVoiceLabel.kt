package com.kingzcheung.xime.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.kingzcheung.xime.R

/**
 * 语音录音/识别期间的系统级悬浮状态窗：挂在 WindowManager（TYPE_APPLICATION_OVERLAY），
 * 位于输入法键盘窗口之外、屏幕中上部，在抖音等全屏 App 中同样可见。
 *
 * - 不抢焦点（FLAG_NOT_FOCUSABLE）、不拦截触摸（FLAG_NOT_TOUCHABLE），手指操作不受影响
 * - 仅在"录音悬浮窗"开关打开且已授予 SYSTEM_ALERT_WINDOW 权限时，由 service show/hide
 * - 生命周期：回到 IDLE 后由 service 延迟 removeView；IME onDestroy 必须兜底 hide 防泄漏
 */
class FloatingVoiceLabel(private val context: Context) {

    // 不能在 init 里取：该对象随 XimeInputMethodService 的属性初始化器创建，
    // 此时 Service 作为 ContextWrapper 尚未 attach base context，getSystemService 会 NPE。
    private var windowManager: WindowManager? = null

    private fun wm(): WindowManager {
        return windowManager
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
                windowManager = it
            }
    }

    private var container: View? = null
    private var statusText: TextView? = null
    private var liveDot: View? = null

    val isShowing: Boolean get() = container != null

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    /**
     * 显示或刷新状态。[isListening] 为 true 时显示红色"录音中"圆点；
     * 识别中/准备录音时圆点隐藏。窗口已存在时只换文案，避免闪烁。
     */
    fun show(text: String, isListening: Boolean) {
        if (container == null) {
            buildView()
        }
        statusText?.text = text
        liveDot?.visibility = if (isListening) View.VISIBLE else View.INVISIBLE
    }

    private fun buildView() {
        val dot = View(context).apply {
            val d = dp(9)
            layoutParams = LinearLayout.LayoutParams(d, d).apply { marginEnd = dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF4D4F"))
            }
        }

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_floating_voice_mic)
            val s = dp(24)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(10) }
        }

        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            // 给文字封顶约六成屏宽（两行），防止窗口超出屏幕
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.60f).toInt()
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = dp(22)
            val padV = dp(15)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.parseColor("#F2111111"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            addView(dot)
            addView(icon)
            addView(tv)
            elevation = dp(12).toFloat()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // minSdk 27，无需版本分支
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 距屏幕顶部 14% 高度：竖屏落在中上部，横屏也不贴边
            verticalMargin = 0.14f
            @Suppress("DEPRECATION")
            windowAnimations = android.R.style.Animation_Translucent
        }

        try {
            wm().addView(row, params)
            container = row
            statusText = tv
            liveDot = dot
        } catch (e: Exception) {
            // 权限被撤销/token 异常等：本次放弃显示，调用方仍保留键盘内胶囊兜底
            container = null
            statusText = null
            liveDot = null
        }
    }

    fun hide() {
        val v = container ?: return
        try {
            wm().removeView(v)
        } catch (_: Exception) {
            // view 已随窗口 token 销毁：忽略，状态照常复位
        }
        container = null
        statusText = null
        liveDot = null
    }
}
