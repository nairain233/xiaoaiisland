package com.xiaoai.islandnotify.hook;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.app.Notification;
import android.graphics.Color;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Chronometer;
import android.widget.TextView;

import com.xiaoai.islandnotify.modernhook.XC_MethodHook;
import com.xiaoai.islandnotify.modernhook.XposedBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.xiaoai.islandnotify.modernhook.XposedHelpers.findAndHookMethod;

public class SystemUiHook {

    private static final String TAG = "IslandNotifySysUI";
    private static final Set<String> TARGET_PACKAGES = ConcurrentHashMap.newKeySet();

    private static final String DYNAMIC_ISLAND_CONTENT_IFACE =
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandContent";
    private static final String DYNAMIC_ISLAND_DATA_CLASS =
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData";
    private static final String EXTRA_BIG_ISLAND_EFFECT = "miui.bigIsland.effect.src";
    private static final String EXTRA_OWNER_KEY = "xiaoai.islandnotify.owner";
    private static final String EXTRA_OWNER_VALUE = "com.xiaoai.islandnotify";
    private static final String FOCUS_CONTENT_CLASS =
            "com.android.systemui.plugins.miui.notification.FocusNotificationContent";
    private static final String FOCUS_NOTIFICATION_CONTROLLER_CLASS =
            "miui.systemui.notification.focus.FocusNotificationController";
    private static final String DEVICE_LISTENER_CLASS =
            "com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl";
    private static final String DEVICE_MODEL_CLASS =
            "com.android.systemui.devicenotification.bean.DeviceNotificationModel";
    private static final String DYNAMIC_ISLAND_ANIMATION_CONTROLLER_CLASS =
            "miui.systemui.dynamicisland.anim.DynamicIslandAnimationController";
    private static final String DYNAMIC_GLOW_EFFECT_VIEW_CLASS =
            "miui.systemui.dynamicisland.view.DynamicGlowEffectView";
    private static final String DYNAMIC_FEATURE_CONFIG_CLASS =
            "miui.systemui.dynamicisland.DynamicFeatureConfig";
    private static final String MODULE_TEXT_VIEW_HOLDER_CLASS =
            "miui.systemui.notification.focus.moduleV3.ModuleTextViewHolder";
    private static final String MODULE_DECO_PORT_TEXT_BUTTON_VIEW_HOLDER_CLASS =
            "miui.systemui.notification.focus.moduleV3.ModuleDecoPortTextButtonViewHolder";
    private static final String MODULE_TEXT_BUTTON_VIEW_HOLDER_CLASS =
            "miui.systemui.notification.focus.moduleV3.ModuleTextButtonViewHolder";
    private static final String MODULE_TINY_TEXT_BUTTON_VIEW_HOLDER_CLASS =
            "miui.systemui.notification.focus.moduleV3.ModuleTinyTextButtonViewHolder";
    private static final String LIGHT_BG_SHADER_FIELD = "U_LIGHT_COLORS";
    private static final String PREFS_GROUP_CONFIG = "island_custom";
    private static final String KEY_STATUS_CUSTOM_GLOW_COLOR_ENABLED = "out_effect_status_custom_color_enabled";
    private static final String KEY_STATUS_CUSTOM_GLOW_COLOR_ARGB = "out_effect_status_custom_color_argb";
    private static final String KEY_EXPAND_CUSTOM_GLOW_COLOR_ENABLED = "out_effect_expand_custom_color_enabled";
    private static final String KEY_EXPAND_CUSTOM_GLOW_COLOR_ARGB = "out_effect_expand_custom_color_argb";
    private static final String KEY_STATUS_GLOW_ENABLED = "out_effect_status_enabled";
    private static final String KEY_EXPAND_GLOW_ENABLED = "out_effect_expand_enabled";
    private static final String BASE_ISLAND_MODULE_VIEW_HOLDER_CLASS =
            "miui.systemui.dynamicisland.module.BaseIslandModuleViewHolder";
    private static final String ISLAND_SAME_WIDTH_DIGIT_VIEW_HOLDER_CLASS =
            "miui.systemui.dynamicisland.module.IslandSameWidthDigitViewHolder";

    private static final ThreadLocal<Boolean> sReentry = new ThreadLocal<>();
    private static final ThreadLocal<Integer> sIslandBindDepth = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> sCurrentBindOwned = new ThreadLocal<>();
    private static final Set<String> sHookedIslandContentClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> sHookedShaderFeatureClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> sHookedAnimationControllerClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> sHookedGlowEffectViewClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> sLoggedOptionalHookFailures = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, float[]> sDefaultLightShaderColors =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean sGlowColorHookDisabled = false;
    private static volatile boolean sCachedStatusCustomGlowEnabled = false;
    private static volatile int sCachedStatusCustomGlowArgb = 0xFFFFFFFF;
    private static volatile boolean sCachedExpandCustomGlowEnabled = false;
    private static volatile int sCachedExpandCustomGlowArgb = 0xFFFFFFFF;
    private static volatile boolean sCachedStatusGlowEnabled = false;
    private static volatile boolean sCachedExpandGlowEnabled = true;
    private static volatile long sLastGlowPrefsReadAt = 0L;
    private static final int GLOW_MODE_AUTO = 0;
    private static final int GLOW_MODE_STATUS = 1;
    private static final int GLOW_MODE_EXPAND = 2;
    private static volatile int sRecentOwnedGlowMode = GLOW_MODE_AUTO;
    private static volatile long sRecentOwnedGlowAt = 0L;
    private static final long RECENT_OWNED_GLOW_TTL_MS = 2500L;
    private static final Map<TextView, Boolean> sAdaptiveWatchers =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, String> sFocusContentKeyMap =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    // bind(Template, StatusBarNotification) 时按 sbn 记录 holder 归属，
    // 供 notifyDataChanged/setViewWidth 等无 sbn 参数的 hook 查询。
    private static final Map<Object, Boolean> sHolderOwned =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> sOwnedNotifyKeys = ConcurrentHashMap.newKeySet();
    private static final Map<ClassLoader, Boolean> sInstalledHookLoaders =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean sBaseDexCtorHooked = false;
    private static volatile boolean sLoadClassHooked = false;
    private static final ExecutorService sHookInstallerExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "IslandNotify-hook-installer");
                t.setDaemon(true);
                return t;
            });

    static {
        TARGET_PACKAGES.add("com.android.systemui");
        TARGET_PACKAGES.add("miui.systemui.plugin");
    }

    public void handleLoadPackage(String packageName, ClassLoader classLoader) {
        if (!TARGET_PACKAGES.contains(packageName)) return;
        if ("com.android.systemui".equals(packageName)) {
            installHooksForClassLoader(classLoader);
            hookPluginClassLoaderBridge(classLoader);
            return;
        }
        installHooksForClassLoader(classLoader);
    }

    private void installHooksForClassLoader(ClassLoader classLoader) {
        if (classLoader == null) return;
        synchronized (sInstalledHookLoaders) {
            if (sInstalledHookLoaders.containsKey(classLoader)) return;
            sInstalledHookLoaders.put(classLoader, Boolean.TRUE);
        }
        hookDynamicIslandShaderFeature(classLoader);
        hookBigIslandAnimationState(classLoader);
        hookGlowEffectStartColor(classLoader);
        hookFocusDynamicIslandExtrasBridge(classLoader);
        hookBaseModuleHolderBind(classLoader);
        hookBaseIslandModuleHolderBind(classLoader);
        hookExactFirstLimitPoints(classLoader);
        hookIslandExpandedView(classLoader);
        hookSameWidthDigitSuffixStyle(classLoader);
        hookSameWidthDigitContentColor(classLoader);
    }

    private void scheduleInstallHooksForClassLoader(ClassLoader classLoader) {
        if (classLoader == null) return;
        try {
            sHookInstallerExecutor.execute(() -> installHooksForClassLoader(classLoader));
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
            installHooksForClassLoader(classLoader);
        }
    }

    private void hookFocusDynamicIslandExtrasBridge(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(FOCUS_NOTIFICATION_CONTROLLER_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"setUpDynamicIslandDataBundle".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 1) continue;
                if (!StatusBarNotification.class.equals(pts[0])) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            if (!(result instanceof Bundle dataBundle)) return;
                            StatusBarNotification sbn = null;
                            if (param.args != null && param.args.length > 0
                                    && param.args[0] instanceof StatusBarNotification notification) {
                                sbn = notification;
                            }
                            Bundle notifExtras = null;
                            if (sbn != null) {
                                Notification n = sbn.getNotification();
                                if (n != null) notifExtras = n.extras;
                            }
                            String bigEffect = dataBundle.getString(EXTRA_BIG_ISLAND_EFFECT, null);
                            String owner = dataBundle.getString(EXTRA_OWNER_KEY, null);
                            if (notifExtras != null) {
                                String nBig = notifExtras.getString(EXTRA_BIG_ISLAND_EFFECT, null);
                                if (!TextUtils.isEmpty(nBig)) {
                                    bigEffect = nBig;
                                }
                                String nOwner = notifExtras.getString(EXTRA_OWNER_KEY, null);
                                if (!TextUtils.isEmpty(nOwner)) {
                                    owner = nOwner;
                                }
                            }
                            if (!TextUtils.isEmpty(bigEffect)) {
                                dataBundle.putString(EXTRA_BIG_ISLAND_EFFECT, bigEffect);
                            }
                            if (!TextUtils.isEmpty(owner)) {
                                dataBundle.putString(EXTRA_OWNER_KEY, owner);
                            }
                        } catch (Throwable failure) {
                            swallowOptionalHookFailure(failure);
                        }
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private Bundle extractExtrasFromDynamicIslandData(Object dataObj) {
        try {
            Object extrasObj = invokeNoArg(dataObj, "getExtras");
            if (extrasObj instanceof Bundle extras) return extras;
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
        return null;
    }

    private boolean isOwnedExtras(Bundle extras) {
        if (extras == null) return false;
        String owner = extras.getString(EXTRA_OWNER_KEY, "");
        return EXTRA_OWNER_VALUE.equals(owner);
    }

    private String extractKeyFromDynamicIslandData(Object dataObj) {
        if (dataObj == null) return "";
        Object keyObj = invokeNoArg(dataObj, "getKey");
        return keyObj instanceof String value ? value : "";
    }

    private boolean isOwnedDynamicIslandData(Object dataObj) {
        Bundle extras = extractExtrasFromDynamicIslandData(dataObj);
        return isOwnedExtras(extras);
    }

    private boolean markOwnedKeyFromDynamicIslandData(Object dataObj) {
        String key = extractKeyFromDynamicIslandData(dataObj);
        if (TextUtils.isEmpty(key)) return false;
        boolean owned = isOwnedDynamicIslandData(dataObj);
        if (owned) {
            sOwnedNotifyKeys.add(key);
        } else {
            sOwnedNotifyKeys.remove(key);
        }
        return owned;
    }

    private boolean isOwnedNotifyKey(String key) {
        return !TextUtils.isEmpty(key) && sOwnedNotifyKeys.contains(key);
    }

    private void hookDynamicIslandShaderFeature(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(DYNAMIC_FEATURE_CONFIG_CLASS, false, classLoader);
            String clsName = cls.getName();
            if (!sHookedShaderFeatureClasses.add(clsName)) return;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"getFEATURE_DYNAMIC_ISLAND_SHADER".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private boolean hasVoiceAssistBigGlowRequest(Object dataObj) {
        Bundle extras = extractExtrasFromDynamicIslandData(dataObj);
        if (extras == null) return false;
        if (!isOwnedExtras(extras)) return false;
        String bigEffect = extras.getString(EXTRA_BIG_ISLAND_EFFECT, "");
        return !TextUtils.isEmpty(bigEffect);
    }

    private void hookBigIslandAnimationState(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(DYNAMIC_ISLAND_ANIMATION_CONTROLLER_CLASS, false, classLoader);
            String clsName = cls.getName();
            if (!sHookedAnimationControllerClasses.add(clsName)) return;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"onStateChange".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object stateObj = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                            if (stateObj == null) return;
                            boolean isBig = isBigIslandStateTag(stateObj);
                            boolean isDeleted = isDeletedIslandStateTag(stateObj);
                            boolean isExpand = isExpandIslandStateTag(stateObj);
                            Object dataObj = extractDynamicDataFromAnimationState(stateObj);
                            markOwnedKeyFromDynamicIslandData(dataObj);
                            updateRecentOwnedGlowState(dataObj, resolveStrictGlowMode(isBig, isExpand));
                            if (!hasVoiceAssistBigGlowRequest(dataObj)) return;
                            Object bigView = invokeNoArg(stateObj, "getBigIslandView");
                            if (bigView == null) return;
                            if (isBig) {
                                // Keep the host's adaptive glow size to avoid halo overflow.
                                invokeStartGlowEffect(bigView);
                            } else if (isDeleted) {
                                invokeStopGlowEffect(bigView);
                            }
                        } catch (Throwable failure) {
                            swallowOptionalHookFailure(failure);
                        }
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private Object extractDynamicDataFromAnimationState(Object stateObj) {
        if (stateObj == null) return null;
        String[] names = new String[] {
                "getCurrentIslandData",
                "getIslandData",
                "getData"
        };
        for (String n : names) {
            try {
                Object value = invokeNoArg(stateObj, n);
                if (value != null) return value;
            } catch (Throwable failure) {
                swallowOptionalHookFailure(failure);
            }
        }
        return null;
    }

    private boolean isBigIslandStateTag(Object stateObj) {
        if (stateObj == null) return false;
        Object state = invokeNoArg(stateObj, "getState");
        String text = String.valueOf(state);
        return text.contains("BigIsland");
    }

    private boolean isDeletedIslandStateTag(Object stateObj) {
        if (stateObj == null) return false;
        Object state = invokeNoArg(stateObj, "getState");
        String text = String.valueOf(state);
        return text.contains("Deleted");
    }

    private boolean isExpandIslandStateTag(Object stateObj) {
        if (stateObj == null) return false;
        Object state = invokeNoArg(stateObj, "getState");
        String text = String.valueOf(state);
        return text.contains("Expand");
    }

    private int resolveStrictGlowMode(boolean isBig, boolean isExpand) {
        // Strict split:
        // 1) status-only state -> status color
        // 2) expand state -> expand color
        // 3) mixed/unknown -> do not force either side to avoid cross-using colors
        if (isExpand && !isBig) return GLOW_MODE_EXPAND;
        if (isBig && !isExpand) return GLOW_MODE_STATUS;
        return GLOW_MODE_AUTO;
    }

    private int resolveGlowModeFromGlowView(Object glowViewObj) {
        if (glowViewObj == null) return GLOW_MODE_AUTO;
        String cls = glowViewObj.getClass().getName();
        if (cls.contains("DynamicIslandExpandedView")) return GLOW_MODE_EXPAND;
        if (cls.contains("DynamicIslandBigIslandView")) return GLOW_MODE_STATUS;
        return GLOW_MODE_AUTO;
    }

    private void hookGlowEffectStartColor(ClassLoader classLoader) {
        try {
            refreshGlowPrefsCache(true);
            Class<?> glowCls = Class.forName(DYNAMIC_GLOW_EFFECT_VIEW_CLASS, false, classLoader);
            String clsName = glowCls.getName();
            if (!sHookedGlowEffectViewClasses.add(clsName)) return;
            for (Method m : glowCls.getDeclaredMethods()) {
                if (!"startGlowEffect$miui_dynamicisland_release".equals(m.getName())
                        && !"startGlowEffect".equals(m.getName())) {
                    continue;
                }
                if (m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (sGlowColorHookDisabled) return;
                        try {
                            Object glowView = param.thisObject;
                            int mode = resolveGlowModeFromGlowView(glowView);
                            if (!shouldApplyOwnedGlowForMode(mode)) return;
                            applyCustomGlowColorForGlowViewInstance(glowView, mode);
                        } catch (Throwable t) {
                            sGlowColorHookDisabled = true;
                            swallowOptionalHookFailure(t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void applyCustomGlowColorForGlowViewInstance(Object glowView, int glowMode) {
        if (glowView == null) return;
        refreshGlowPrefsCache(false);
        Object shaderObj = resolveLightBgShaderFromGlowView(glowView);
        if (shaderObj == null) return;
        Object runtimeShader = resolveTextureShaderFromLightBgShader(shaderObj);
        if (runtimeShader == null) return;
        Class<?> shaderCls = shaderObj.getClass();
        float[] currentOrDefault = getDefaultLightShaderArrayOrCurrent(shaderCls);
        if (currentOrDefault == null || currentOrDefault.length == 0) return;
        GlowColorConfig cfg = resolveGlowColorConfig(glowMode);
        float[] target = (!cfg.effectEnabled || !cfg.customEnabled)
                ? currentOrDefault
                : rebuildLightShaderArray(currentOrDefault, cfg.argb);
        setRuntimeShaderLightColors(runtimeShader, target);
    }

    private void updateRecentOwnedGlowState(Object dataObj, int mode) {
        if (mode == GLOW_MODE_AUTO) return;
        if (isOwnedDynamicIslandData(dataObj)) {
            sRecentOwnedGlowMode = mode;
            sRecentOwnedGlowAt = System.currentTimeMillis();
        }
    }

    private boolean shouldApplyOwnedGlowForMode(int mode) {
        if (mode == GLOW_MODE_AUTO) return false;
        long now = System.currentTimeMillis();
        if (now - sRecentOwnedGlowAt > RECENT_OWNED_GLOW_TTL_MS) return false;
        return sRecentOwnedGlowMode == mode;
    }

    private Object resolveLightBgShaderFromGlowView(Object glowView) {
        Object container = invokeNoArg(glowView, "getMContainer");
        if (container == null) return null;
        Object shaderObj = invokeNoArg(container, "getMShader$hyper_widget_1_0_8_pluginRelease");
        if (shaderObj != null) return shaderObj;
        try {
            for (Method m : container.getClass().getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (!m.getName().contains("getMShader")) continue;
                m.setAccessible(true);
                Object out = m.invoke(container);
                if (out != null) return out;
            }
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
        return null;
    }

    private Object resolveTextureShaderFromLightBgShader(Object lightBgShader) {
        if (lightBgShader == null) return null;
        try {
            Method m = lightBgShader.getClass().getDeclaredMethod("getMTextureShader");
            m.setAccessible(true);
            return m.invoke(lightBgShader);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
        try {
            for (Method m : lightBgShader.getClass().getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (!m.getName().contains("getMTextureShader")) continue;
                m.setAccessible(true);
                Object out = m.invoke(lightBgShader);
                if (out != null) return out;
            }
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
        return null;
    }

    private void setRuntimeShaderLightColors(Object runtimeShader, float[] colors) {
        if (runtimeShader == null || colors == null || colors.length == 0) return;
        try {
            Method m = runtimeShader.getClass().getMethod("setFloatUniform", String.class, float[].class);
            m.setAccessible(true);
            m.invoke(runtimeShader, "uLightColors", colors);
            return;
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
        try {
            for (Method m : runtimeShader.getClass().getMethods()) {
                if (!"setFloatUniform".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 2) continue;
                if (!String.class.equals(pts[0])) continue;
                if (!pts[1].isArray()) continue;
                m.setAccessible(true);
                m.invoke(runtimeShader, "uLightColors", colors);
                break;
            }
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
    }

    private float[] getDefaultLightShaderArrayOrCurrent(Class<?> shaderCls) {
        if (shaderCls == null) return null;
        float[] current = getLightShaderArray(shaderCls);
        if (current != null && current.length > 0) {
            cacheDefaultLightShaderArray(shaderCls, current);
        }
        float[] def = sDefaultLightShaderColors.get(shaderCls);
        if (def == null || def.length == 0) return current;
        return Arrays.copyOf(def, def.length);
    }

    private void invokeStartGlowEffect(Object target) {
        if (target == null) return;
        String[] names = new String[] {
                "startGlowEffect$miui_dynamicisland_release",
                "startGlowEffect"
        };
        for (String n : names) {
            try {
                Method m = target.getClass().getMethod(n);
                m.setAccessible(true);
                m.invoke(target);
                return;
            } catch (Throwable failure) {
                swallowOptionalHookFailure(failure);
            }
        }
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (!m.getName().contains("startGlowEffect")) continue;
                m.setAccessible(true);
                m.invoke(target);
                break;
            }
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
    }

    private void invokeStopGlowEffect(Object target) {
        if (target == null) return;
        String[] names = new String[] {
                "stopGlowEffect$miui_dynamicisland_release",
                "stopGlowEffect"
        };
        for (String n : names) {
            try {
                Method m = target.getClass().getMethod(n, boolean.class);
                m.setAccessible(true);
                m.invoke(target, Boolean.TRUE);
                return;
            } catch (Throwable failure) {
                swallowOptionalHookFailure(failure);
            }
            try {
                Method m = target.getClass().getMethod(n);
                m.setAccessible(true);
                m.invoke(target);
                return;
            } catch (Throwable failure) {
                swallowOptionalHookFailure(failure);
            }
        }
    }

    private void hookPluginClassLoaderBridge(ClassLoader classLoader) {
        if (!sLoadClassHooked) {
            synchronized (SystemUiHook.class) {
                if (!sLoadClassHooked) {
                    try {
                        findAndHookMethod("java.lang.ClassLoader", classLoader,
                                "loadClass", String.class, boolean.class, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) {
                                        if (!(param.thisObject instanceof ClassLoader hit)) return;
                                        if (param.args == null || param.args.length == 0) return;
                                        Object nameObj = param.args[0];
                                        if (!(nameObj instanceof String name)) return;
                                        if (!isFocusModuleClassName(name)) return;
                                        scheduleInstallHooksForClassLoader(hit);
                                    }
                                });
                        sLoadClassHooked = true;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": hook ClassLoader#loadClass bridge failed -> " + t.getMessage());
                    }
                }
            }
        }
        if (!sBaseDexCtorHooked) {
            synchronized (SystemUiHook.class) {
                if (!sBaseDexCtorHooked) {
                    try {
                        Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader", false, classLoader);
                        for (Constructor<?> c : baseDex.getDeclaredConstructors()) {
                            c.setAccessible(true);
                            XposedBridge.hookMethod(c, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    if (!(param.thisObject instanceof ClassLoader cl)) return;
                                    if (!isLikelyPluginClassLoader(cl)) return;
                                    scheduleInstallHooksForClassLoader(cl);
                                }
                            });
                        }
                        sBaseDexCtorHooked = true;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": hook BaseDexClassLoader bridge failed -> " + t.getMessage());
                    }
                }
            }
        }
    }

    private boolean isFocusModuleClassName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        return name.startsWith("miui.systemui.notification.focus.moduleV3.")
                || name.startsWith("miui.systemui.notification.focus.templateV3.")
                || name.startsWith("miui.systemui.dynamicisland.");
    }

    private boolean isLikelyPluginClassLoader(ClassLoader cl) {
        if (cl == null) return false;
        String s = String.valueOf(cl);
        if (TextUtils.isEmpty(s)) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("sysui_component")
                || lower.contains("miui.systemui.plugin")
                || lower.contains("systemui_component");
    }

    private static void swallowOptionalHookFailure(Throwable failure) {
        // 不同 ROM 可缺少可选类或方法；仅对此类探测失败静默。
        Throwable cause = failure;
        while (cause instanceof IllegalStateException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ClassNotFoundException || cause instanceof NoSuchMethodException
                || cause instanceof NoSuchFieldException) {
            return;
        }
        Throwable rootCause = cause;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        StackTraceElement[] frames = new Throwable().getStackTrace();
        String callSite = frames.length > 1 ? frames[1].toString() : SystemUiHook.class.getName();
        String signature = callSite + "|" + rootCause.getClass().getName() + "|" + rootCause.getMessage();
        // 同一调用位置、根因类型及消息在当前进程只记录首次堆栈，避免高频宿主回调刷屏。
        if (!sLoggedOptionalHookFailures.add(signature)) return;
        try {
            XposedBridge.log(TAG + ": optional hook failed");
            XposedBridge.log(failure);
        } catch (RuntimeException loggingFailure) {
            // 日志接口故障也不能中断宿主回调，退回系统日志保留原异常。
            android.util.Log.e(TAG, "Unable to write optional hook failure to Xposed log: " + loggingFailure, failure);
        }
    }

    private GlowColorConfig resolveGlowColorConfig(int glowMode) {
        if (glowMode == GLOW_MODE_STATUS) {
            boolean effectEnabled = sCachedStatusGlowEnabled;
            boolean customEnabled = sCachedStatusCustomGlowEnabled;
            int argb = sCachedStatusCustomGlowArgb;
            return new GlowColorConfig(effectEnabled, customEnabled, argb);
        } else if (glowMode == GLOW_MODE_EXPAND) {
            boolean effectEnabled = sCachedExpandGlowEnabled;
            boolean customEnabled = sCachedExpandCustomGlowEnabled;
            int argb = sCachedExpandCustomGlowArgb;
            return new GlowColorConfig(effectEnabled, customEnabled, argb);
        } else {
            // Unknown view type: don't force either side's custom color to avoid cross contamination.
            boolean effectEnabled = false;
            boolean customEnabled = false;
            int argb = 0xFFFFFFFF;
            return new GlowColorConfig(effectEnabled, customEnabled, argb);
        }
    }

    private void refreshGlowPrefsCache(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - sLastGlowPrefsReadAt < 1000L) return;
        SharedPreferences prefs = readModuleConfigPrefs();
        if (prefs == null) return;
        sCachedStatusGlowEnabled = prefs.getBoolean(KEY_STATUS_GLOW_ENABLED, false);
        sCachedExpandGlowEnabled = prefs.getBoolean(KEY_EXPAND_GLOW_ENABLED, true);
        sCachedStatusCustomGlowEnabled = prefs.getBoolean(KEY_STATUS_CUSTOM_GLOW_COLOR_ENABLED, false);
        sCachedStatusCustomGlowArgb = prefs.getInt(KEY_STATUS_CUSTOM_GLOW_COLOR_ARGB, 0xFFFFFFFF);
        sCachedExpandCustomGlowEnabled = prefs.getBoolean(KEY_EXPAND_CUSTOM_GLOW_COLOR_ENABLED, false);
        sCachedExpandCustomGlowArgb = prefs.getInt(KEY_EXPAND_CUSTOM_GLOW_COLOR_ARGB, 0xFFFFFFFF);
        sLastGlowPrefsReadAt = now;
    }

    private static final class GlowColorConfig {
        final boolean effectEnabled;
        final boolean customEnabled;
        final int argb;

        GlowColorConfig(boolean effectEnabled, boolean customEnabled, int argb) {
            this.effectEnabled = effectEnabled;
            this.customEnabled = customEnabled;
            this.argb = argb;
        }
    }

    private SharedPreferences readModuleConfigPrefs() {
        try {
            return XposedBridge.getRemotePreferences(PREFS_GROUP_CONFIG);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
            return null;
        }
    }

    private void cacheDefaultLightShaderArray(Class<?> shaderCls, float[] currentArray) {
        if (shaderCls == null || currentArray == null || currentArray.length == 0) return;
        if (sDefaultLightShaderColors.containsKey(shaderCls)) return;
        sDefaultLightShaderColors.put(shaderCls, Arrays.copyOf(currentArray, currentArray.length));
    }

    private float[] rebuildLightShaderArray(float[] base, int argb) {
        // Official-like mapping:
        // keep the default palette curve (S/V structure), remap it to the user hue.
        float[] tpl = normalizeTemplatePalette(base);
        float[] out = new float[tpl.length];
        float[] seedHsv = new float[3];
        Color.colorToHSV(argb, seedHsv);
        float seedH = seedHsv[0];
        float seedS = clamp01(seedHsv[1]);
        float seedV = clamp01(seedHsv[2]);

        float[] anchorHsv = extractStopHsv(tpl, 2);
        float anchorHue = anchorHsv[0];
        float anchorS = Math.max(0.01f, anchorHsv[1]);
        float anchorV = Math.max(0.01f, anchorHsv[2]);
        float[] svMinMax = calcTemplateSvMinMax(tpl);
        float minS = svMinMax[0];
        float maxS = svMinMax[1];
        float minV = svMinMax[2];
        float maxV = svMinMax[3];

        int stops = tpl.length / 3;
        for (int i = 0; i < stops; i++) {
            float[] thsv = extractStopHsv(tpl, i);
            float tplHueDelta = shortestHueDelta(anchorHue, thsv[0]);
            float h = wrapHue(seedH + tplHueDelta * 0.45f);
            float sNorm = normalize01(thsv[1], minS, maxS);
            float vNorm = normalize01(thsv[2], minV, maxV);
            float s = clamp01(seedS * (0.72f + 0.38f * sNorm) * (thsv[1] / anchorS));
            float v = clamp01(seedV * (0.62f + 0.48f * vNorm) * (thsv[2] / anchorV));
            int c = Color.HSVToColor(new float[] {h, s, v});
            int baseIndex = i * 3;
            out[baseIndex] = Color.red(c) / 255f;
            out[baseIndex + 1] = Color.green(c) / 255f;
            out[baseIndex + 2] = Color.blue(c) / 255f;
        }
        return out;
    }

    private float[] normalizeTemplatePalette(float[] base) {
        if (base != null && base.length >= 33) {
            return Arrays.copyOf(base, 33);
        }
        // Fallback: official default-like constants.
        return new float[] {
                0.502f, 0.525f, 1.0f,
                1.0f, 0.827f, 0.702f,
                1.0f, 0.525f, 0.208f,
                0.518f, 0.494f, 1.0f,
                0.071f, 0.412f, 0.949f,
                0.502f, 0.525f, 1.0f,
                1.0f, 0.827f, 0.702f,
                1.0f, 0.525f, 0.208f,
                0.518f, 0.494f, 1.0f,
                0.071f, 0.412f, 0.949f,
                1.0f, 0.525f, 0.208f
        };
    }

    private float[] extractStopHsv(float[] rgb33, int stopIndex) {
        int idx = stopIndex * 3;
        int r = (int) (clamp01(rgb33[idx]) * 255f);
        int g = (int) (clamp01(rgb33[idx + 1]) * 255f);
        int b = (int) (clamp01(rgb33[idx + 2]) * 255f);
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        return hsv;
    }

    private float[] calcTemplateSvMinMax(float[] rgb33) {
        float minS = 1f;
        float maxS = 0f;
        float minV = 1f;
        float maxV = 0f;
        int stops = rgb33.length / 3;
        for (int i = 0; i < stops; i++) {
            float[] hsv = extractStopHsv(rgb33, i);
            if (hsv[1] < minS) minS = hsv[1];
            if (hsv[1] > maxS) maxS = hsv[1];
            if (hsv[2] < minV) minV = hsv[2];
            if (hsv[2] > maxV) maxV = hsv[2];
        }
        return new float[] {minS, maxS, minV, maxV};
    }

    private float normalize01(float x, float min, float max) {
        float d = max - min;
        if (d <= 1e-6f) return 0.5f;
        return clamp01((x - min) / d);
    }

    private float shortestHueDelta(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private float wrapHue(float h) {
        float out = h % 360f;
        return out < 0f ? out + 360f : out;
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private float[] getLightShaderArray(Class<?> shaderCls) {
        try {
            Field f = findField(shaderCls, LIGHT_BG_SHADER_FIELD);
            if (f == null) return null;
            f.setAccessible(true);
            Object obj = f.get(null);
            if (!(obj instanceof float[] colors)) return null;
            return colors;
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
            return null;
        }
    }

    private void hookExactFirstLimitPoints(ClassLoader classLoader) {
        // 仅保留“主要小文本2(subTitle)”相关限制修正，避免影响未展开岛A区域宽度。
        hookFocusSmallSubtitleFirstLimit(classLoader);
        hookFinalTitleMarqueePoints(classLoader);
    }

    private void hookBaseModuleHolderBind(ClassLoader classLoader) {
        // 所有 moduleV3 holder 的 bind(Template, StatusBarNotification) 都先调
        // super.bind()，在此按 sbn 记录 holder 归属，供无 sbn 参数的下游 hook 查询。
        try {
            Class<?> cls = Class.forName("miui.systemui.notification.focus.moduleV3.ModuleViewHolder", false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"bind".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 2) continue;
                if (!"miui.systemui.notification.focus.model.Template".equals(pts[0].getName())) continue;
                if (!StatusBarNotification.class.equals(pts[1])) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        markHolderOwnership(param.thisObject, param.args);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void hookBaseIslandModuleHolderBind(ClassLoader classLoader) {
        // dynamicisland.module 一支的 bind(IslandTemplate, DynamicIslandData) 都先调
        // super.bind()，归属信号是 DynamicIslandData.getExtras() 里的 owner 标记。
        try {
            Class<?> cls = Class.forName(BASE_ISLAND_MODULE_VIEW_HOLDER_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"bind".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 2) continue;
                if (!DYNAMIC_ISLAND_DATA_CLASS.equals(pts[1].getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        markIslandHolderOwnership(param.thisObject, param.args);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void hookFinalTitleMarqueePoints(ClassLoader classLoader) {
        hookNotifyDataChangedMarquee(classLoader, MODULE_TEXT_BUTTON_VIEW_HOLDER_CLASS);
        hookNotifyDataChangedMarquee(classLoader, MODULE_TINY_TEXT_BUTTON_VIEW_HOLDER_CLASS);
        hookNotifyDataChangedMarquee(classLoader, MODULE_TEXT_VIEW_HOLDER_CLASS);
    }

    private void hookSameWidthDigitSuffixStyle(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(ISLAND_SAME_WIDTH_DIGIT_VIEW_HOLDER_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"bind".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 2) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isHolderUnowned(param.thisObject)) return;
                        Object self = param.thisObject;
                        Object digitObj = getFieldValue(self, "sameWidthDigit");
                        Object titleObj = getFieldValue(self, "title");
                        Object contentObj = getFieldValue(self, "content");
                        if (!(contentObj instanceof TextView content)) return;
                        enforceSameWidthDigitTypeface(self);
                        TextView source = null;
                        if (digitObj instanceof TextView digit) {
                            if (digit.getVisibility() == View.VISIBLE) source = digit;
                        }
                        if (source == null && titleObj instanceof TextView title) {
                            if (title.getVisibility() == View.VISIBLE) source = title;
                        }
                        if (source == null && titleObj instanceof TextView title) {
                            source = title;
                        }
                        if (source == null) return;
                        harmonizeSuffixStyle(source, content);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void enforceSameWidthDigitTypeface(Object holder) {
        if (holder == null) return;
        Object d = getFieldValue(holder, "sameWidthDigit");
        Object t = getFieldValue(holder, "title");
        Object c = getFieldValue(holder, "content");
        TextView digit = d instanceof TextView view ? view : null;
        TextView title = t instanceof TextView view ? view : null;
        TextView content = c instanceof TextView view ? view : null;
        android.graphics.Typeface tf = android.graphics.Typeface.create("mipro-demibold", android.graphics.Typeface.NORMAL);
        sReentry.set(Boolean.TRUE);
        try {
            if (digit != null) {
                digit.setTypeface(tf);
                digit.setTextScaleX(1f);
                digit.setLetterSpacing(0f);
            }
            if (title != null) {
                title.setTypeface(tf);
                title.setTextScaleX(1f);
                title.setLetterSpacing(0f);
            }
            if (content != null) {
                content.setTypeface(tf);
                content.setTextScaleX(1f);
                content.setLetterSpacing(0f);
            }
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        } finally {
            sReentry.set(Boolean.FALSE);
        }
    }

    private void hookSameWidthDigitContentColor(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(BASE_ISLAND_MODULE_VIEW_HOLDER_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"setContentHighlightColor".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 4) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isHolderUnowned(param.thisObject)) return;
                        Object self = param.thisObject;
                        if (!ISLAND_SAME_WIDTH_DIGIT_VIEW_HOLDER_CLASS.equals(self.getClass().getName())) return;
                        if (param.args == null || param.args.length < 4) return;
                        Object contentObj = param.args[2];
                        Object textObj = param.args[3];
                        if (!(contentObj instanceof TextView content)) return;
                        String text = textObj instanceof String value ? value : null;
                        if (text == null) return;

                        TextView src = null;
                        Object digitObj = getFieldValue(self, "sameWidthDigit");
                        if (digitObj instanceof TextView digit && digit.getVisibility() == View.VISIBLE) {
                            src = digit;
                        }
                        if (src == null) {
                            Object titleObj = getFieldValue(self, "title");
                            if (titleObj instanceof TextView title) src = title;
                        }
                        int color = src != null ? src.getCurrentTextColor() : content.getCurrentTextColor();
                        sReentry.set(Boolean.TRUE);
                        try {
                            invokeUpdateTextWithColor(content, text, color);
                            content.setTextColor(color);
                        } catch (Throwable failure) {
                            swallowOptionalHookFailure(failure);
                        } finally {
                            sReentry.set(Boolean.FALSE);
                        }
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void invokeUpdateTextWithColor(TextView target, String text, int color) {
        if (target == null) return;
        try {
            Method m = target.getClass().getMethod("updateTextWithNewAppearance", CharSequence.class, Integer.class);
            m.setAccessible(true);
            m.invoke(target, text, color);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
            target.setText(text, TextView.BufferType.SPANNABLE);
        }
    }

    private void harmonizeSuffixStyle(TextView title, TextView content) {
        if (title == null || content == null) return;
        sReentry.set(Boolean.TRUE);
        try {
            applyExactIslandTitleStyle(content);
            content.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, title.getTextSize());
            content.setTypeface(android.graphics.Typeface.create("mipro-demibold", android.graphics.Typeface.NORMAL));
            if (title.getTextColors() != null) {
                content.setTextColor(title.getTextColors());
            } else {
                content.setTextColor(title.getCurrentTextColor());
            }
            content.setAlpha(title.getAlpha());
            content.setIncludeFontPadding(title.getIncludeFontPadding());
            content.setGravity(title.getGravity());
            content.setAllCaps(title.isAllCaps());
            content.setElegantTextHeight(title.isElegantTextHeight());
            content.setFallbackLineSpacing(title.isFallbackLineSpacing());
            content.setShadowLayer(title.getShadowRadius(), title.getShadowDx(), title.getShadowDy(), title.getShadowColor());
            content.setLineSpacing(title.getLineSpacingExtra(), title.getLineSpacingMultiplier());
            content.setMinHeight(title.getMinHeight());
            content.setMinWidth(title.getMinWidth());
            content.setLineHeight(title.getLineHeight());
            ViewGroup.LayoutParams lp = content.getLayoutParams();
            ViewGroup.LayoutParams titleLp = title.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams mlp) {
                if (titleLp instanceof ViewGroup.MarginLayoutParams tmlp) {
                    mlp.leftMargin = tmlp.leftMargin;
                    mlp.topMargin = tmlp.topMargin;
                    mlp.rightMargin = tmlp.rightMargin;
                    mlp.bottomMargin = tmlp.bottomMargin;
                } else {
                    mlp.leftMargin = 0;
                    mlp.topMargin = 0;
                    mlp.rightMargin = 0;
                    mlp.bottomMargin = 0;
                }
                content.setLayoutParams(mlp);
            }
            content.setPadding(title.getPaddingLeft(), title.getPaddingTop(), title.getPaddingRight(), title.getPaddingBottom());
            content.requestLayout();
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        } finally {
            sReentry.set(Boolean.FALSE);
        }
    }

    // 样式由宿主或其插件定义，模块不能使用自身 R 引用。
    @android.annotation.SuppressLint("DiscouragedApi")
    private void applyExactIslandTitleStyle(TextView target) {
        if (target == null) return;
        try {
            android.content.Context ctx = target.getContext();
            if (ctx == null) return;
            android.content.res.Resources res = ctx.getResources();
            if (res == null) return;
            String pkg = ctx.getPackageName();
            int styleId = res.getIdentifier("IslandTitleStyle", "style", pkg);
            if (styleId == 0) {
                styleId = res.getIdentifier("IslandTitleStyle", "style", "miui.systemui.plugin");
            }
            if (styleId != 0) {
                target.setTextAppearance(styleId);
            }
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
    }

    private void hookNotifyDataChangedMarquee(ClassLoader classLoader, String className) {
        try {
            Class<?> cls = Class.forName(className, false, classLoader);
            Method m = null;
            for (Method mm : cls.getDeclaredMethods()) {
                if (!"notifyDataChanged".equals(mm.getName())) continue;
                if (mm.getParameterTypes().length != 0) continue;
                m = mm;
                break;
            }
            if (m == null) return;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isHolderUnowned(param.thisObject)) return;
                    Object self = param.thisObject;
                    applyMarqueeForFieldTextView(self, "focusSmallTitle");
                    applyMarqueeForFieldTextView(self, "focusTitle");
                    applyMarqueeForFieldTextView(self, "focusTitleView");
                }
            });
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
    }

    private void applyMarqueeForFieldTextView(Object holder, String fieldName) {
        if (holder == null || TextUtils.isEmpty(fieldName)) return;
        try {
            Field f = findField(holder.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            Object obj = f.get(holder);
            if (!(obj instanceof TextView tv)) return;
            if (TextUtils.isEmpty(tv.getText())) return;
            ensureAdaptiveWatcher(tv);
            applyAdaptiveMarquee(tv);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
    }

    private void hookFocusSmallSubtitleFirstLimit(ClassLoader classLoader) {
        hookSubtitleWidthInSetViewWidth(classLoader);
        hookModuleTextButton2Bind(classLoader);
        hookModuleBindForSubtitle(classLoader);
    }

    private void hookModuleTextButton2Bind(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(MODULE_TEXT_BUTTON_VIEW_HOLDER_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"bind".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 2) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isHolderUnowned(param.thisObject)) return;
                        Object self = param.thisObject;
                        Object tpl = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                        ensureSubTitleFieldVisible(self, tpl, "smallSubTitle");
                        syncPureTimerToSubtitle(self, tpl, "smallSubTitle", "chronometerHint");
                        clearTitleWhenPureTimer(self, tpl);
                        fixTimerTitleAndSubtitleLeakForTextButton(self);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void hookSubtitleWidthInSetViewWidth(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName("miui.systemui.notification.focus.moduleV3.ModuleViewHolder", false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"setViewWidth".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 3) continue;
                if (p[0] != TextView.class || p[1] != int.class || p[2] != int.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isHolderUnowned(param.thisObject)) return;
                        if (param.args == null || param.args.length < 3) return;
                        Object tvObj = param.args[0];
                        if (!(tvObj instanceof TextView tv)) return;
                        if (!isSubtitleTargetView(tv)) return;

                        CharSequence cs = tv.getText();
                        if (TextUtils.isEmpty(cs)) return;

                        int desired = (int) Math.ceil(tv.getPaint().measureText(cs.toString()))
                                + tv.getPaddingLeft() + tv.getPaddingRight();
                        ViewParent parent = tv.getParent();
                        if (parent instanceof View pv) {
                            int available = pv.getWidth() - pv.getPaddingLeft() - pv.getPaddingRight();
                            if (available > 0) desired = Math.min(desired, available);
                        }
                        if (desired <= 0) return;

                        int oldW = param.args[1] instanceof Integer value ? value : 0;
                        int minW = param.args[2] instanceof Integer value ? value : 0;
                        if (oldW < desired) param.args[1] = desired;
                        if (minW < 1) param.args[2] = 1;
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isHolderUnowned(param.thisObject)) return;
                        if (param.args == null || param.args.length < 1) return;
                        Object tvObj = param.args[0];
                        if (!(tvObj instanceof TextView tv)) return;
                        boolean subtitleTarget = isSubtitleTargetView(tv);
                        boolean titleTarget = isTitleTargetView(tv);
                        if (!subtitleTarget && !titleTarget) return;
                        if (TextUtils.isEmpty(tv.getText())) return;
                        if (titleTarget) {
                            ensureAdaptiveWatcher(tv);
                            applyAdaptiveMarquee(tv);
                            return;
                        }
                        sReentry.set(Boolean.TRUE);
                        try {
                            tv.setVisibility(View.VISIBLE);
                        } catch (Throwable failure) {
                            swallowOptionalHookFailure(failure);
                        } finally {
                            sReentry.set(Boolean.FALSE);
                        }
                    }
                });
                return;
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private boolean isSubtitleTargetView(TextView tv) {
        if (tv == null) return false;
        int id = tv.getId();
        if (id != View.NO_ID) {
            String idName = safeIdName(tv, id).toLowerCase(Locale.ROOT);
            if (idName.contains("small_subtitle")) return true;
            if (idName.contains("small_sub_title")) return true;
            if (idName.contains("focus_small_subtitle")) return true;
            if (idName.contains("focus_small_sub_title")) return true;
            return idName.endsWith("subtitle") || idName.endsWith("sub_title");
        }
        return false;
    }

    private boolean isTitleTargetView(TextView tv) {
        if (tv == null) return false;
        int id = tv.getId();
        if (id == View.NO_ID) return false;
        String idName = safeIdName(tv, id).toLowerCase(Locale.ROOT);
        if (idName.contains("small_subtitle") || idName.contains("sub_title") || idName.contains("subtitle")) {
            return false;
        }
        return idName.contains("focus_small_title") || idName.equals("focus_title")
                || (idName.endsWith("_title") && idName.contains("focus"));
    }

    private void hookModuleBindForSubtitle(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(MODULE_DECO_PORT_TEXT_BUTTON_VIEW_HOLDER_CLASS, false, classLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"bind".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 2) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isHolderUnowned(param.thisObject)) return;
                        Object self = param.thisObject;
                        // hintInfo.type=2(按钮组件2): subTitle -> focusSmallSubtitleView
                        Object tpl = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                        ensureSubTitleFieldVisible(self, tpl, "focusSmallSubtitleView");
                        syncPureTimerToSubtitle(self, tpl, "focusSmallSubtitleView", "chronometerHintView");
                        clearTitleWhenPureTimer(self, tpl);
                        fixTimerTitleAndSubtitleLeakForDecoPort(self);
                        forceSmallSubtitleNoFirstTrim(self);
                        // 组件2里 title 也在同一容器，避免相互挤压时再次触发首段省略
                    }
                });
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void forceSmallSubtitleNoFirstTrim(Object host) {
        Object tvObj = getFieldValue(host, "focusSmallSubtitleView");
        if (!(tvObj instanceof TextView tv)) return;
        sReentry.set(Boolean.TRUE);
        try {
            tv.setMaxWidth(Integer.MAX_VALUE / 4);
            tv.setMaxEms(Integer.MAX_VALUE / 4);
            tv.setSingleLine(true);
            tv.setMaxLines(1);
            tv.setHorizontallyScrolling(false);
            tv.setEllipsize(null);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        } finally {
            sReentry.set(Boolean.FALSE);
        }
        ensureAdaptiveWatcher(tv);
        applyAdaptiveMarquee(tv);
    }

    private void ensureSubTitleFieldVisible(Object host, Object templateObj, String fieldName) {
        Object tvObj = getFieldValue(host, fieldName);
        if (!(tvObj instanceof TextView tv)) return;
        String text = extractSubTitle(host, templateObj);
        if (TextUtils.isEmpty(text)) {
            // 显式清空，避免Recycler/复用导致上一阶段文本残留
            sReentry.set(Boolean.TRUE);
            try {
                tv.setText("");
                tv.setSelected(false);
                tv.setEllipsize(null);
                tv.setHorizontallyScrolling(false);
            } catch (Throwable failure) {
                swallowOptionalHookFailure(failure);
            } finally {
                sReentry.set(Boolean.FALSE);
            }
            return;
        }
        sReentry.set(Boolean.TRUE);
        try {
            tv.setVisibility(View.VISIBLE);
            tv.setText(text);
            tv.setSingleLine(true);
            tv.setMaxLines(1);
            tv.setHorizontallyScrolling(false);
            tv.setEllipsize(null);
            ViewGroup.LayoutParams lp = tv.getLayoutParams();
            if (lp != null && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                tv.setLayoutParams(lp);
            }
            tv.requestLayout();
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        } finally {
            sReentry.set(Boolean.FALSE);
        }
        ensureAdaptiveWatcher(tv);
        applyAdaptiveMarquee(tv);
    }

    private void clearTitleWhenPureTimer(Object host, Object templateObj) {
        if (host == null || templateObj == null) return;
        try {
            Object hint = invokeNoArg(templateObj, "getHintInfo");
            if (hint == null) return;
            Object timerInfo = invokeNoArg(hint, "getTimerInfo");
            Object titleObj = invokeNoArg(hint, "getTitle");
            String title = titleObj instanceof String value ? value : "";
            if (timerInfo == null) return;
            if (!TextUtils.isEmpty(title)) return;
            clearTitleField(host, "focusSmallTitle");
            clearTitleField(host, "focusTitle");
            clearTitleField(host, "focusTitleView");
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
    }

    private void clearTitleField(Object host, String fieldName) {
        Object tvObj = getFieldValue(host, fieldName);
        if (!(tvObj instanceof TextView tv)) return;
        sReentry.set(Boolean.TRUE);
        try {
            tv.setText("");
            tv.setSelected(false);
            tv.setEllipsize(null);
            tv.setHorizontallyScrolling(false);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        } finally {
            sReentry.set(Boolean.FALSE);
        }
    }

    private void fixTimerTitleAndSubtitleLeakForTextButton(Object host) {
        if (host == null) return;
        try {
            Object chronoObj = getFieldValue(host, "chronometerHint");
            Object titleObj = getFieldValue(host, "focusSmallTitle");
            Object subObj = getFieldValue(host, "smallSubTitle");
            if (chronoObj instanceof View chrono && titleObj instanceof TextView titleTv) {
                if (chrono.getVisibility() == View.VISIBLE) {
                    clearTextViewNow(titleTv);
                }
            }
            String subtitle = "";
            Object subStr = invokeNoArg(host, "getSubtitle");
            if (subStr instanceof String value) subtitle = value;
            if (subObj instanceof TextView subtitleView && TextUtils.isEmpty(subtitle)) {
                clearTextViewNow(subtitleView);
            }
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
    }

    private void fixTimerTitleAndSubtitleLeakForDecoPort(Object host) {
        if (host == null) return;
        try {
            Object chronoObj = getFieldValue(host, "chronometerHintView");
            Object titleObj = getFieldValue(host, "focusSmallTitleView");
            Object subObj = getFieldValue(host, "focusSmallSubtitleView");
            if (chronoObj instanceof View chrono && titleObj instanceof TextView titleTv) {
                if (chrono.getVisibility() == View.VISIBLE) {
                    clearTextViewNow(titleTv);
                }
            }
            String subtitle = "";
            Object subStr = invokeNoArg(host, "getSubtitle");
            if (subStr instanceof String value) subtitle = value;
            if (subObj instanceof TextView subtitleView && TextUtils.isEmpty(subtitle)) {
                clearTextViewNow(subtitleView);
            }
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
    }

    private void clearTextViewNow(TextView tv) {
        if (tv == null) return;
        sReentry.set(Boolean.TRUE);
        try {
            tv.setText("");
            tv.setVisibility(View.GONE);
            tv.setSelected(false);
            tv.setEllipsize(null);
            tv.setHorizontallyScrolling(false);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        } finally {
            sReentry.set(Boolean.FALSE);
        }
    }

    private void syncPureTimerToSubtitle(Object host, Object templateObj, String subFieldName, String chronoFieldName) {
        if (host == null || templateObj == null) return;
        try {
            Object hint = invokeNoArg(templateObj, "getHintInfo");
            if (hint == null) return;
            Object timerInfo = invokeNoArg(hint, "getTimerInfo");
            if (timerInfo == null) return;
            Object subObj = invokeNoArg(hint, "getSubTitle");
            String sub = subObj instanceof String value ? value.trim() : "";
            if (!"{倒计时}".equals(sub) && !"{正计时}".equals(sub)) return;

            Object subTvObj = getFieldValue(host, subFieldName);
            Object chronoObj = getFieldValue(host, chronoFieldName);
            if (!(subTvObj instanceof TextView subTv) || !(chronoObj instanceof TextView chronoTv)) return;

            sReentry.set(Boolean.TRUE);
            try {
                subTv.setVisibility(View.VISIBLE);
                subTv.setText(chronoTv.getText());
                subTv.setSingleLine(true);
                subTv.setMaxLines(1);
                subTv.setEllipsize(null);
                subTv.setHorizontallyScrolling(false);
            } finally {
                sReentry.set(Boolean.FALSE);
            }

            if (chronoObj instanceof Chronometer chronometer) {
                chronometer.setOnChronometerTickListener(c -> {
                    try {
                        sReentry.set(Boolean.TRUE);
                        subTv.setText(c.getText());
                    } catch (Throwable failure) {
                        swallowOptionalHookFailure(failure);
                    } finally {
                        sReentry.set(Boolean.FALSE);
                    }
                });
            }
            ensureAdaptiveWatcher(subTv);
            applyAdaptiveMarquee(subTv);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
    }

    private String extractSubTitle(Object host, Object templateObj) {
        try {
            if (templateObj != null) {
                Object hint = invokeNoArg(templateObj, "getHintInfo");
                Object sub = hint == null ? null : invokeNoArg(hint, "getSubTitle");
                if (sub instanceof String subtitle && !TextUtils.isEmpty(subtitle)) {
                    return subtitle;
                }
            }
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
        Object hostSub = invokeNoArg(host, "getSubtitle");
        return hostSub instanceof String value ? value : "";
    }

    private void hookIslandExpandedView(ClassLoader classLoader) {
        try {
            findAndHookMethod(FOCUS_CONTENT_CLASS, classLoader,
                    "setIslandExpandedView", View.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!resolveOwnedForFocusContent(param.thisObject)) return;
                            View root = (param.args != null && param.args.length > 0 && param.args[0] instanceof View view)
                                    ? view : null;
                            tuneIslandViewTree(root);
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }

        try {
            findAndHookMethod(FOCUS_CONTENT_CLASS, classLoader,
                    "setIslandExpandedViewFake", View.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!resolveOwnedForFocusContent(param.thisObject)) return;
                            View root = (param.args != null && param.args.length > 0 && param.args[0] instanceof View view)
                                    ? view : null;
                            tuneIslandViewTree(root);
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }

        hookFocusSetter(classLoader, "setFocusNotification");
        hookFocusSetter(classLoader, "setFocusNotificationDark");
        hookFocusSetter(classLoader, "setFocusNotificationModal");
        hookFocusSetter(classLoader, "setFocusNotificationDarkModal");
        hookFocusSetter(classLoader, "setTinyView");
        hookFocusSetter(classLoader, "setTinyViewDark");
        hookFocusSetter(classLoader, "setTinyViewModal");
        hookFocusSetter(classLoader, "setTinyViewDarkModal");
        hookFocusSetter(classLoader, "setTinyKeyguardView");
        hookFocusSetter(classLoader, "setTinyViewKeyguardDark");
        hookFocusKeySetter(classLoader);

        try {
            findAndHookMethod(DEVICE_LISTENER_CLASS, classLoader,
                    "handleDeviceNotification", Bundle.class, DEVICE_MODEL_CLASS,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Ensure runtime island content hooks track owned notification keys.
                            installRuntimeIslandContentHook(param.thisObject);
                            // 不再改写岛A/B文本，避免未展开态宽度异常挤压状态栏图标。
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void hookFocusSetter(ClassLoader classLoader, final String methodName) {
        try {
            findAndHookMethod(FOCUS_CONTENT_CLASS, classLoader,
                    methodName, View.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            enterIslandBind();
                            boolean owned = resolveOwnedForFocusContent(param.thisObject);
                            sCurrentBindOwned.set(owned);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sCurrentBindOwned.set(Boolean.FALSE);
                            exitIslandBind();
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private boolean resolveOwnedForFocusContent(Object focusContentObj) {
        if (focusContentObj == null) return false;
        String key = sFocusContentKeyMap.get(focusContentObj);
        if (TextUtils.isEmpty(key)) {
            Object keyObj = invokeNoArg(focusContentObj, "getKey");
            if (keyObj instanceof String value) {
                key = value;
                if (!TextUtils.isEmpty(key)) {
                    sFocusContentKeyMap.put(focusContentObj, key);
                }
            }
        }
        return isOwnedNotifyKey(key);
    }

    private void hookFocusKeySetter(ClassLoader classLoader) {
        try {
            findAndHookMethod(FOCUS_CONTENT_CLASS, classLoader,
                    "setKey", String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object self = param.thisObject;
                            if (self == null) return;
                            String key = (param.args != null && param.args.length > 0 && param.args[0] instanceof String value)
                                    ? value : "";
                            if (!TextUtils.isEmpty(key)) {
                                sFocusContentKeyMap.put(self, key);
                            }
                        }
                    });
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private void installRuntimeIslandContentHook(Object listenerObj) {
        try {
            Object handler = getFieldValue(listenerObj, "deviceNotificationHandler");
            if (handler == null) return;
            Object controller = getFieldValue(handler, "controller");
            if (controller == null) return;

            Object content = invokeGetContent(controller);
            if (content == null) return;
            Class<?> cls = content.getClass();
            String clsName = cls.getName();
            if (!sHookedIslandContentClasses.add(clsName)) return;

            Class<?> cur = cls;
            while (cur != null) {
                for (Method m : cur.getDeclaredMethods()) {
                    String name = m.getName();
                    if (!("addDynamicIslandView".equals(name) || "updateDynamicIslandView".equals(name))) continue;
                    if (m.getParameterTypes().length != 2) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            enterIslandBind();
                            boolean owned = false;
                            if (param.args != null && param.args.length > 0) {
                                owned = markOwnedKeyFromDynamicIslandData(param.args[0]);
                            }
                            sCurrentBindOwned.set(owned);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sCurrentBindOwned.set(Boolean.FALSE);
                            exitIslandBind();
                        }
                    });
                }
                cur = cur.getSuperclass();
            }
        } catch (Throwable t) {
            swallowOptionalHookFailure(t);
        }
    }

    private Object invokeGetContent(Object controller) {
        for (Method m : controller.getClass().getMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            Class<?> ret = m.getReturnType();
            if (!DYNAMIC_ISLAND_CONTENT_IFACE.equals(ret.getName())) continue;
            try {
                m.setAccessible(true);
                return m.invoke(controller);
            } catch (Throwable failure) {
                swallowOptionalHookFailure(failure);
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
            return null;
        }
    }

    private static void tuneIslandViewTree(View root) {
        if (root == null) return;
        if (root instanceof TextView textView) {
            tuneIslandText(textView);
        }
        if (root instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                tuneIslandViewTree(vg.getChildAt(i));
            }
        }
    }

    private static void tuneIslandText(TextView tv) {
        try {
            tv.setSingleLine(true);
            tv.setMaxLines(1);
            tv.setHorizontallyScrolling(false);
            tv.setEllipsize(null);
            tv.setSelected(false);
            tv.setFocusable(false);
            tv.setFocusableInTouchMode(false);
            tv.setMaxWidth(Integer.MAX_VALUE / 4);
            tv.requestLayout();
            ensureAdaptiveWatcher(tv);
            applyAdaptiveMarquee(tv);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
        }
    }

    private static void ensureAdaptiveWatcher(final TextView tv) {
        if (tv == null) return;
        if (sAdaptiveWatchers.containsKey(tv)) return;
        sAdaptiveWatchers.put(tv, Boolean.TRUE);
        tv.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                applyAdaptiveMarquee(tv));
    }

    private static void applyAdaptiveMarquee(final TextView tv) {
        if (tv == null) return;
        Runnable task = () -> {
            if (tv.getLayout() == null) return;
            CharSequence cs = tv.getText();
            if (cs == null) return;
            float textWidth = tv.getPaint().measureText(cs.toString());
            int available = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
            if (available <= 0) return;
            boolean needMarquee = textWidth > (available + 1f);

            sReentry.set(Boolean.TRUE);
            try {
                tv.setHorizontallyScrolling(needMarquee);
                tv.setEllipsize(needMarquee ? TextUtils.TruncateAt.MARQUEE : null);
                tv.setSelected(needMarquee);
                tv.setFocusable(needMarquee);
                tv.setFocusableInTouchMode(needMarquee);
                if (needMarquee) tv.setMarqueeRepeatLimit(-1);
            } finally {
                sReentry.set(Boolean.FALSE);
            }
        };
        tv.post(task);
    }

    private static void enterIslandBind() {
        Integer depth = sIslandBindDepth.get();
        if (depth == null) depth = 0;
        sIslandBindDepth.set(depth + 1);
    }

    private static void exitIslandBind() {
        Integer depth = sIslandBindDepth.get();
        if (depth == null || depth <= 1) {
            sIslandBindDepth.set(0);
            sCurrentBindOwned.set(Boolean.FALSE);
            return;
        }
        sIslandBindDepth.set(depth - 1);
    }

    private boolean isOwnedStatusBarNotification(Object sbnObj) {
        if (!(sbnObj instanceof StatusBarNotification sbn)) return false;
        if (EXTRA_OWNER_VALUE.equals(sbn.getPackageName())) return true;
        Notification n = sbn.getNotification();
        return n != null && n.extras != null
                && EXTRA_OWNER_VALUE.equals(n.extras.getString(EXTRA_OWNER_KEY, ""));
    }

    private void markHolderOwnership(Object holder, Object[] args) {
        boolean owned = args != null && args.length > 1 && isOwnedStatusBarNotification(args[1]);
        if (holder == null) return;
        if (owned) {
            sHolderOwned.put(holder, Boolean.TRUE);
        } else {
            sHolderOwned.remove(holder);
        }
    }

    private void markIslandHolderOwnership(Object holder, Object[] args) {
        boolean owned = args != null && args.length > 1
                && isOwnedDynamicIslandData(args[1]);
        if (holder == null) return;
        if (owned) {
            sHolderOwned.put(holder, Boolean.TRUE);
        } else {
            sHolderOwned.remove(holder);
        }
    }

    private static boolean isHolderUnowned(Object holder) {
        return holder == null || !Boolean.TRUE.equals(sHolderOwned.get(holder));
    }

    private static String safeIdName(View view, int id) {
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (android.content.res.Resources.NotFoundException ignored) {
            // 宿主生成的 View ID 不一定对应资源；探测失败正常回退为空名称。
            return "";
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
            return "";
        }
    }

    private static Object getFieldValue(Object target, String fieldName) {
        if (target == null) return null;
        try {
            Field field = findField(target.getClass(), fieldName);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable failure) {
            swallowOptionalHookFailure(failure);
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

}
