package de.timowa.expenselog.DependencyInjection;

import android.app.Activity;
import android.content.Context;

import dagger.Module;
import dagger.Provides;

@Module
public class ActivityModule {
    private Activity activity;
//    private AppCompatActivity appCompatActivity;

    public ActivityModule(Activity activity) {
        this.activity = activity;
    }

    @Provides
    Activity activity() {
        return activity;
    }

//    @Provides
//    AppCompatActivity appCompatActivity() {
//        return appCompatActivity;
//    }
    @Provides
    Context provideContext() {
        return activity.getApplicationContext();
    }
}