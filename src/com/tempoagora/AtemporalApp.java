package com.tempoagora;

import android.app.Application;

/** Instala o registro de travamentos antes de qualquer tela ou widget. */
public final class AtemporalApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        CrashReporter.install(this);
    }
}
