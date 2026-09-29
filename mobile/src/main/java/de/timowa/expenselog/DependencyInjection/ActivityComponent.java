package de.timowa.expenselog.DependencyInjection;

import de.timowa.expenselog.AccountListFragment;
import de.timowa.expenselog.LogGraphFragment;
import de.timowa.expenselog.LogListFragment;
import de.timowa.expenselog.LogTabsFragment;
import de.timowa.expenselog.LogsCalendarFragment;
import de.timowa.expenselog.MainActivity;
import de.timowa.expenselog.NewCategoryFragment;
import de.timowa.expenselog.NewLogFragment;
import de.timowa.expenselog.RecurringListFragment;
import de.timowa.expenselog.SettingsActivity;
import de.timowa.expenselog.SpreadsheetHelper;
import de.timowa.expenselog.SummaryFragment;
import de.timowa.expenselog.TagListFragment;
import de.timowa.expenselog.dropbox.BackupListActivity;
import dagger.Component;

@ActivityScope
@Component(dependencies = AppComponent.class, modules = ActivityModule.class)
public interface ActivityComponent {

    MainActivity injectActivity(MainActivity activity);

    SettingsActivity injectActivity(SettingsActivity activity);

    BackupListActivity injectActivity(BackupListActivity activity);

    void inject(SettingsActivity.DataSyncPreferenceFragment dataSyncPreferenceFragment);

    void inject(SpreadsheetHelper spreadsheetHelper);

    void inject(NewLogFragment newLogFragment);

    void inject(TagListFragment tagListFragment);

    void inject(NewCategoryFragment newCategoryFragment);

    void inject(LogTabsFragment logTabsFragment);

    void inject(LogListFragment logListFragment);

    void inject(SummaryFragment summaryFragment);

    void inject(AccountListFragment accountListFragment);

    void inject(LogGraphFragment logGraphFragment);

    void inject(LogsCalendarFragment logsCalendarFragment);

    void inject(RecurringListFragment recurringListFragment);

    //Exposed to sub-graphs.
}
