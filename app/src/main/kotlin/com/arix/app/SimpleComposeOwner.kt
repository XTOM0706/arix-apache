package com.arix.app

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 供在非-Activity 窗口（VoiceInteractionSession / WindowManager overlay）里跑 Compose 用的
 * 最小 Lifecycle / SavedState / ViewModelStore 宿主。ComposeView 需要这三个 ViewTree owner。
 */
class SimpleComposeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val reg = LifecycleRegistry(this)
    private val vms = ViewModelStore()
    private val ssc = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = reg
    override val viewModelStore: ViewModelStore get() = vms
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry

    fun onCreate() {
        if (reg.currentState == Lifecycle.State.DESTROYED) return
        ssc.performRestore(null)
        reg.currentState = Lifecycle.State.CREATED
    }

    /** 已销毁的 registry 不能再上移，否则 IllegalStateException(DESTROYED→RESUMED)崩溃。 */
    fun onResume() {
        if (reg.currentState == Lifecycle.State.DESTROYED) return
        reg.currentState = Lifecycle.State.RESUMED
    }

    /** 隐藏时暂停(降到 CREATED)而非销毁，以便会话再次 show 时能 onResume 复用。 */
    fun onPause() {
        if (reg.currentState.isAtLeast(Lifecycle.State.CREATED)) reg.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        if (reg.currentState.isAtLeast(Lifecycle.State.CREATED)) reg.currentState = Lifecycle.State.DESTROYED
    }

    fun attach(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
