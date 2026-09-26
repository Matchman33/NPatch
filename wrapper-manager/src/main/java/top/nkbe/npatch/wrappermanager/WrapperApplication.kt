package top.nkbe.npatch.wrappermanager

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

class WrapperApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val model: WrapperViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[WrapperViewModel::class.java]
    }
}
