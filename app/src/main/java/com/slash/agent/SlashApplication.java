package com.slash.agent;

import android.app.Application;

public final class SlashApplication extends Application {
    private ChatCoordinator coordinator;

    @Override public void onCreate() {
        super.onCreate();
        coordinator = new ChatCoordinator(this);
    }

    public ChatCoordinator coordinator() { return coordinator; }
}
