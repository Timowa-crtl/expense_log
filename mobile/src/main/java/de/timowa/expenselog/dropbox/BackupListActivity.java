package de.timowa.expenselog.dropbox;

import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.os.BundleCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.DependencyInjection.ActivityModule;
import de.timowa.expenselog.DependencyInjection.DaggerActivityComponent;
import de.timowa.expenselog.DependencyInjection.HasComponent;
import de.timowa.expenselog.DropBoxHelper;
import de.timowa.expenselog.MainActivity;
import de.timowa.expenselog.MyLogApplication;
import de.timowa.expenselog.R;

/**
 * The backups held in the user's Dropbox: tap one for Delete or Restore.
 *
 * <p>An {@link AppCompatActivity} over a {@link RecyclerView}. It was a {@code ListActivity},
 * deprecated since API 30, which bound its list by the framework ids {@code @android:id/list} and
 * {@code @android:id/empty} and gave the screen no toolbar at all.
 */
public class BackupListActivity extends AppCompatActivity implements HasComponent<ActivityComponent> {

    private static final int MENU_DELETE = 234;
    private static final int MENU_RESTORE = 3413;
    private static final int CONTEXT_MENU_ID = 7823;

    private List<String> backupsFilesList = Collections.emptyList();
    private List<String> backupsTimeList = Collections.emptyList();

    /** The row the context menu was opened on, resolved from that row's view. */
    private int selectedPosition = -1;

    private RecyclerView list;

    private ActivityComponent activityComponent;

    @Inject
    DropBoxHelper dropBoxHelper;

    @Override
    public ActivityComponent getComponent() {
        return activityComponent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backups_list);

        Toolbar toolbar = findViewById(R.id.backups_toolbar);
        setSupportActionBar(toolbar);
        setTitle(R.string.backups);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        this.activityComponent = DaggerActivityComponent.builder()
                .appComponent(((MyLogApplication) getApplication()).getComponent())
                .activityModule(new ActivityModule(this))
                .build();
        activityComponent.injectActivity(this);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            ArrayList<String> files = BundleCompat.getSerializable(extras, "list", ArrayList.class);
            ArrayList<String> times = BundleCompat.getSerializable(extras, "timeList", ArrayList.class);
            if (files != null) backupsFilesList = files;
            if (times != null) backupsTimeList = times;
        }

        list = findViewById(R.id.recyclerView_backups);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new BackupsAdapter());

        TextView empty = findViewById(R.id.textView_backups_empty);
        boolean none = backupsTimeList.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Builds the menu for the row {@code v} belongs to.
     *
     * <p>The position is read from the view the menu was opened on, not remembered from an earlier
     * tap: a remembered one goes stale. The menu used to be registered on the RecyclerView itself,
     * which made it long-clickable as a whole, so a long press on the empty space below the last
     * row opened a menu still pointing at whatever was tapped before -- and Delete would then
     * delete a backup the user had not chosen.
     */
    @Override
    public void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View v,
                                    ContextMenu.ContextMenuInfo menuInfo) {
        RecyclerView.ViewHolder holder = list == null ? null : list.findContainingViewHolder(v);
        selectedPosition = holder == null ? -1 : holder.getBindingAdapterPosition();
        if (selectedPosition < 0 || selectedPosition >= backupsFilesList.size()) {
            // Not a row: offer nothing rather than act on a stale index.
            return;
        }
        menu.add(CONTEXT_MENU_ID, MENU_DELETE, 10, R.string.menu_delete);
        menu.add(CONTEXT_MENU_ID, MENU_RESTORE, 9, R.string.import_title);
    }

    @Override
    public boolean onContextItemSelected(@NonNull final MenuItem item) {
        if (item.getGroupId() != CONTEXT_MENU_ID) return super.onContextItemSelected(item);

        final int listItemSelected = selectedPosition;
        if (listItemSelected < 0 || listItemSelected >= backupsFilesList.size()) {
            return super.onContextItemSelected(item);
        }
        if (BuildConfig.DEBUG)
            Log.i(MainActivity.DROPBOX_TAG, "item and file: " + listItemSelected + " "
                    + backupsFilesList.get(listItemSelected));

        if (item.getItemId() == MENU_DELETE) {
            // warn user they're about to delete
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.dropbox_delete_backup_warning))
                    .setPositiveButton(getString(R.string.menu_delete), (dialog, which) -> {
                        dropBoxHelper.dropboxDeleteRemoteFile(backupsFilesList.get(listItemSelected));
                        finish();
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        } else if (item.getItemId() == MENU_RESTORE) {
            // warn user they're about to replace the local database
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.import_database))
                    .setMessage(getString(R.string.dropbox_delete_and_import_warning))
                    .setPositiveButton(getString(R.string.import_title), (dialog, which) ->
                            dropBoxHelper.dropboxImportBackup(backupsFilesList.get(listItemSelected)))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        }
        return super.onContextItemSelected(item);
    }

    /** One row per backup, showing the time it was taken. */
    private class BackupsAdapter extends RecyclerView.Adapter<BackupsAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_1, parent, false);
            // A tap opens the same context menu the long press does: the list offers nothing but
            // Delete and Restore, so a plain tap that did nothing would be a dead end.
            // The menu is registered per row, not on the list, so it can only be opened on one --
            // and here rather than in onBindViewHolder, which runs again on every scroll.
            registerForContextMenu(view);
            view.setOnClickListener(View::showContextMenu);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.text.setText(backupsTimeList.get(position));
        }

        @Override
        public int getItemCount() {
            return backupsTimeList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView text;

            ViewHolder(View itemView) {
                super(itemView);
                text = itemView.findViewById(android.R.id.text1);
            }
        }
    }
}
