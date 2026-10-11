package io.aodbridge.hook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 诊断扫描：当所有锚点都落空（或容器字段没找到）时，遍历 SystemUI 自身 dex 的类名，
 * 把 Oplus AOD 相关类（以及最像“记录/布局/时钟”的几个类的方法签名）打进日志。
 *
 * 目的：ColorOS 16 若改了类名/字段名，实机第一轮日志就能直接定位新锚点，不用反复试。
 * 全程只读 + try/catch，只在需要时跑一次。
 */
final class Diag {
    private static volatile boolean dumped;

    static void dumpOnce(ClassLoader classLoader, String reason) {
        if (dumped || classLoader == null) {
            return;
        }
        dumped = true;
        AodInjector.log(Log.INFO, "diag start (" + reason + ") " + version());
        try {
            dumpDexClasses(classLoader);
        } catch (Throwable t) {
            AodInjector.log(Log.WARN, "diag dex scan failed: " + t);
        }
    }

    private static String version() {
        String info = "display=" + android.os.Build.DISPLAY + " sdk=" + android.os.Build.VERSION.SDK_INT;
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                android.content.pm.PackageInfo info2 = ((android.content.Context) app).getPackageManager()
                        .getPackageInfo("com.android.systemui", 0);
                info += " systemui=" + info2.versionName + "(" + info2.getLongVersionCode() + ")";
            }
        } catch (Throwable ignored) {
        }
        return info;
    }

    private static void dumpDexClasses(ClassLoader classLoader) throws Exception {
        Object pathList = get(classLoader, "pathList");
        Object[] elements = pathList == null ? null : (Object[]) get(pathList, "dexElements");
        if (elements == null) {
            AodInjector.log(Log.WARN, "diag: dexElements not reachable");
            return;
        }
        TreeSet<String> candidates = new TreeSet<>();
        int total = 0;
        for (Object element : elements) {
            if (element == null) {
                continue;
            }
            Object dexFile = get(element, "dexFile");
            if (dexFile == null) {
                continue;
            }
            Enumeration<?> names = (Enumeration<?>) dexFile.getClass().getMethod("entries").invoke(dexFile);
            while (names != null && names.hasMoreElements()) {
                Object raw = names.nextElement();
                if (!(raw instanceof String)) {
                    continue;
                }
                total++;
                String name = (String) raw;
                if (isCandidate(name)) {
                    candidates.add(name);
                }
            }
        }
        AodInjector.log(Log.INFO, "diag scanned " + total + " classes, candidates=" + candidates.size());
        int shown = 0;
        for (String name : candidates) {
            AodInjector.log(Log.INFO, "diag class: " + name);
            if (++shown >= 100) {
                AodInjector.log(Log.INFO, "diag class list truncated at 100");
                break;
            }
        }
        int detailed = 0;
        for (String name : candidates) {
            if (detailed >= 8) {
                break;
            }
            if (!name.startsWith("com.oplus.systemui.aod.")) {
                continue;
            }
            if (!name.contains("Record") && !name.contains("Layout")
                    && !name.contains("Clock") && !name.contains("Aod")) {
                continue;
            }
            describe(classLoader, name);
            detailed++;
        }
    }

    private static boolean isCandidate(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.startsWith("com.oplus.systemui.aod")) {
            return true;
        }
        return lower.startsWith("com.oplus") && lower.contains("aod");
    }

    private static void describe(ClassLoader classLoader, String name) {
        try {
            Class<?> cls = Class.forName(name, false, classLoader);
            StringBuilder sb = new StringBuilder("diag api: ").append(name).append(" {");
            int n = 0;
            for (Method method : cls.getDeclaredMethods()) {
                if (n++ >= 25) {
                    sb.append(" ...");
                    break;
                }
                sb.append(' ').append(method.getName()).append('/').append(method.getParameterCount());
            }
            sb.append(" }");
            AodInjector.log(Log.INFO, sb.toString());
        } catch (Throwable t) {
            AodInjector.log(Log.WARN, "diag api failed " + name + ": " + t);
        }
    }

    private static Object get(Object target, String fieldName) {
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Field field = cls.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private Diag() {
    }
}
