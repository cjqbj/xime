package com.kingzcheung.xime.service

data class VoiceButtonState(
    val bottomActive: Boolean = false,
    val leftActive: Boolean = false,
    val rightActive: Boolean = false,
    // 按住说话上滑到顶部中央热区：松手切换"录音时静音其他应用"
    val muteActive: Boolean = false
)
