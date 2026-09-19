package com.kingzcheung.xime.util

object RpcLogViewController {
    @JvmStatic
    fun setBackgroundColor(value: String) {
        RpcUiController.setState("log.background", value)
    }
}