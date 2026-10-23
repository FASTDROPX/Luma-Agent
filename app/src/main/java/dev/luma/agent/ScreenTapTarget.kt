package dev.luma.agent

import android.view.accessibility.AccessibilityNodeInfo

internal object ScreenTapTarget {
    /** Web renderers can accept ACTION_CLICK without navigating. Use one real tap, never click twice. */
    @Suppress("DEPRECATION")
    fun needsGesture(target: AccessibilityNodeInfo): Boolean {
        if(!target.isClickable) return true
        var current: AccessibilityNodeInfo?=AccessibilityNodeInfo.obtain(target)
        try {
            repeat(24) {
                val node=current ?: return false
                if(node.className?.toString()?.contains("WebView",ignoreCase=true)==true) return true
                current=node.parent;node.recycle()
            }
            return false
        } finally {current?.recycle()}
    }
}
