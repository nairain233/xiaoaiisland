package com.xiaoai.islandnotify;

import android.app.Application;
import androidx.annotation.NonNull;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class IslandNotifyApp extends Application {

    private static volatile XposedService sService;
    private static volatile boolean sFrameworkActive;
    private static volatile String sFrameworkDesc = "";

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(@NonNull XposedService service) {
                sService = service;
                sFrameworkActive = true;
                int apiVersion = 0;
                try {
                    apiVersion = service.getApiVersion();
                } catch (Throwable ignored) {
                }
                sFrameworkDesc = "Framework: " + service.getFrameworkName()
                        + "\nAPI: " + apiVersion
                        + "  Version: " + service.getFrameworkVersionCode();
                ComposeRefreshBus.bump();
            }

            @Override
            public void onServiceDied(@NonNull XposedService service) {
                sService = null;
                sFrameworkActive = false;
                sFrameworkDesc = "";
                ComposeRefreshBus.bump();
            }
        });
    }

    public static XposedService currentService() {
        return sService;
    }

    public static boolean isFrameworkActive() {
        return sFrameworkActive;
    }

    public static String frameworkDesc() {
        return sFrameworkDesc;
    }
}
