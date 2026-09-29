package de.timowa.expenselog.DependencyInjection;

import android.app.Application;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class AppModule {

    private final Application application;

    public AppModule(Application application) {
        this.application = application;
    }

    /**
     * The app's preferences. Everything that actually reads a preference goes through
     * {@code PreferenceManager.getDefaultSharedPreferences}, so this provides that same store.
     * Until the Step 7 rename it named a preferences file of its own, under the old package
     * namespace, that nothing had ever written to. Nothing injects this provider today, so the
     * mismatch was invisible — the first class to inject it would have been handed an empty
     * store.
     */
    @Provides
    @Singleton
    SharedPreferences provideSharedPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(application);
    }

}
