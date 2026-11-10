package cn.kylebing.meifeng.wubi

import android.app.Application
import cn.kylebing.meifeng.wubi.data.AppPaths
import cn.kylebing.meifeng.wubi.data.WubiEngine
import cn.kylebing.meifeng.wubi.sync.UserDictAutoSync

class MeifengApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        UserDictAutoSync.install()
        UserDictAutoSync.activate()
        WubiEngine.shared.post { WubiEngine.shared.start() }
    }
}
