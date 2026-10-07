package com.kingzcheung.xime.service

import android.content.Context
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

class VoiceKeyboardContainer(
    context: Context,
    private val uiStateProvider: () -> InputUIState,
    private val onUiStateChanged: (InputUIState) -> Unit,
    private val onPerformVibration: (View) -> Unit,
    private val onPerformUndo: () -> Unit,
    private val onPerformSearch: () -> Unit,
    private val onStopRecognition: () -> Unit,
    private val isRecording: () -> Boolean,
    private val setRecording: (Boolean) -> Unit,
    private val onVoiceDismiss: () -> Unit = {},
    private val onTouchCancel: () -> Unit = {},
    private val onToggleMute: () -> Unit = {},
) : FrameLayout(context) {

    private var isTrackingVoiceButtons = false
    private var lastZone: String? = null

    // 普通模式下本容器为 MATCH_PARENT（整屏高），键盘内容只占底部一块。
    // 记录键盘内容高度（px），热区按键盘区几何划分；<=0 时回退按容器高度 60% 划分。
    private var keyboardContentHeightPx: Int = 0

    fun setKeyboardContentHeightPx(px: Int) {
        keyboardContentHeightPx = px
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f
    }

    fun enableVoiceButtonTracking() {
        isTrackingVoiceButtons = true
    }

    // "按住说话"底部条的顶边（相对容器的 y，px）：键盘内容区下 40%。
    // 容器全屏时不能用 height*0.6，否则整个键盘都会落在底部区，上部热区永远摸不到。
    private fun bottomZoneTopY(): Float =
        if (keyboardContentHeightPx > 0) {
            height - keyboardContentHeightPx * 0.4f
        } else {
            height * 0.6f
        }

    fun updateHeight(heightDp: Int) {
        val heightPx = (heightDp * resources.displayMetrics.density).toInt()
        val params = layoutParams
        if (params != null && params.height != heightPx) {
            params.height = heightPx
            layoutParams = params
            requestLayout()
        }
    }

    fun resetHeight() {
        val params = layoutParams
        if (params != null && params.height != FrameLayout.LayoutParams.MATCH_PARENT) {
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            layoutParams = params
            requestLayout()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            when (it.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleActionDown(it)
                }

                MotionEvent.ACTION_UP -> {
                    handleActionUp()
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 系统手势（如三指截图）截走触摸流时，IME 收不到 UP，Compose 手势也不会被取消。
                    // 这里把 cancel 上抛，触发活动键盘 remount 来取消所有进行中的手势协程。
                    handleActionUp()
                    onTouchCancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    handleActionMove(it)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleActionDown(ev: MotionEvent) {
        val uiState = uiStateProvider()
        val isVoiceMode = uiState.isVoiceMode && !uiState.voiceSticky

        lastZone = null

        if (isVoiceMode) {
            val yThreshold = bottomZoneTopY()

            if (ev.y > yThreshold) {
                isTrackingVoiceButtons = true
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(bottomActive = true)
                ))
            }
        }
    }

    private fun handleActionUp() {
        val state = uiStateProvider()

        // 常驻语音（工具栏进入）不拦截触摸：空格键/工具栏自行结束语音
        if (state.voiceSticky) {
            isTrackingVoiceButtons = false
            lastZone = null
            return
        }

        if (state.isVoiceMode || isRecording()) {
            if (state.voiceButtonState.leftActive) {
                onPerformUndo()
            } else if (state.voiceButtonState.rightActive) {
                onPerformSearch()
            } else if (state.voiceButtonState.muteActive) {
                // 上滑到顶部中央热区松手：切换"录音时静音其他应用"（对下次录音生效）
                onToggleMute()
            }

            if (isRecording()) {
                onStopRecognition()
                setRecording(false)
            }

            if (state.isVoiceMode) {
                onVoiceDismiss()
            }
        }

        isTrackingVoiceButtons = false
        lastZone = null
    }

    private fun handleActionMove(ev: MotionEvent) {
        val isVoiceMode = uiStateProvider().isVoiceMode && !uiStateProvider().voiceSticky

        if (isVoiceMode && isTrackingVoiceButtons) {
            val yThreshold = bottomZoneTopY()
            val leftButtonEnd = width * 0.25f
            val rightButtonStart = width * 0.75f

            // 分区：底部（y>60%）左/右=撤销/发送、中=按住说话；
            // 上部左/右同上滑撤销/发送，上部中央=切换"录音时静音其他应用"
            val zone = when {
                ev.x < leftButtonEnd -> "left"
                ev.x > rightButtonStart -> "right"
                ev.y > yThreshold -> "bottom"
                else -> "mute"
            }

            // 仅在手指进入新区域时震动一次
            if (zone != lastZone) {
                onPerformVibration(this@VoiceKeyboardContainer)
                lastZone = zone
            }

            val newState = when (zone) {
                "left" -> VoiceButtonState(leftActive = true)
                "right" -> VoiceButtonState(rightActive = true)
                "bottom" -> VoiceButtonState(bottomActive = true)
                else -> VoiceButtonState(muteActive = true)
            }
            onUiStateChanged(uiStateProvider().copy(voiceButtonState = newState))
        }
    }
}
