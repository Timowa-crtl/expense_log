package de.timowa.expenselog.DependencyInjection;

import javax.inject.Singleton;

import de.timowa.expenselog.MyLogApplication;
import dagger.Component;

@Singleton
@Component (modules = {AppModule.class})
public interface AppComponent {
    MyLogApplication injectApplication(MyLogApplication application);

}
