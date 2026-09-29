package de.timowa.expenselog;

import android.app.Application;


import de.timowa.expenselog.DependencyInjection.AppComponent;
import de.timowa.expenselog.DependencyInjection.AppModule;
import de.timowa.expenselog.DependencyInjection.DaggerAppComponent;

public class MyLogApplication extends Application {
    private AppComponent component;
//    private Tracker mTracker;

    public MyLogApplication() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        component = DaggerAppComponent.builder()
                .appModule(new AppModule(this))
                .build();
        component.injectApplication(this);

    }

    public AppComponent getComponent() {
        return component;
    }
}
