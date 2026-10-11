package io.aodbridge.hook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LSPosed API 102 入口。
 *
 * 作用域（scope.list）只有 com.android.systemui：ColorOS 16 的 AOD 视图
 * （com.oplus.systemui.aod.*）跑在 SystemUI 进程里，由 AodInjector 注入文本。
 */
public class HookEntry extends XposedModule {
    private static final String SYSTEM_UI = "com.android.systemui";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        AodInjector.log(Log.INFO, "onModuleLoaded process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!SYSTEM_UI.equals(param.getPackageName())) {
            // 不能 detach：同一进程可能先后上报多个包，detach 会让 SystemUI 的回调也收不到。
            AodInjector.log(Log.INFO, "skip package " + param.getPackageName());
            return;
        }
        AodInjector.log(Log.INFO, "onPackageLoaded " + param.getPackageName());
        try {
            AodInjector.install(this, param.getDefaultClassLoader());
        } catch (Throwable t) {
            AodInjector.log(Log.ERROR, "install failed: " + t);
        }
    }
}
