package de.timowa.expenselog;

import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Both roots {@code FileHelper.getAppFilesDir} can return must be shareable — finding 3 of
 * {@code docs/history/REVIEW_FINDINGS.md}.
 *
 * <p>{@code getAppFilesDir} falls back to {@code getFilesDir()} when {@code getExternalFilesDir}
 * returns null, which is what happens when external storage is not mounted. {@code
 * provider_paths.xml} declared only {@code <external-files-path>}, so {@code
 * FileProvider.getUriForFile} threw {@code IllegalArgumentException: Failed to find configured root}
 * for anything in that fallback. {@code FileHelper.exportDB} caught it into an error-333 message;
 * {@code SpreadsheetHelper.sendFile} did not, so a CSV export crashed the app outright.
 *
 * <p>Deliberately uses the <b>real</b> application context and creates no files.
 * {@code getUriForFile} maps a path against the declared roots and does not require the file to
 * exist, so this asserts the manifest declaration itself without writing anything anywhere. The
 * isolation harness would be actively wrong here: its directories live under the cache and are not
 * what {@code provider_paths.xml} describes.
 *
 * <p>This is why the fallback could not be caught by the Step 4 device testing: it needs external
 * storage to be unavailable, which on a modern phone effectively never happens. The declaration can
 * be checked directly, so it is.
 */
@RunWith(AndroidJUnit4.class)
public class FileProviderPathsTest {

    private Context context;
    private String authority;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        authority = context.getPackageName() + ".provider";
    }

    /** The normal destination: {@code Android/data/<package>/files}. */
    @Test
    public void externalFilesRoot_isShareable() {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            // Nothing to assert: this is the very case the fallback below exists for.
            return;
        }
        assertNotNull(FileProvider.getUriForFile(context, authority, new File(dir, "probe.csv")));
    }

    /**
     * The fallback, and the whole finding. Before the fix this threw
     * {@code Failed to find configured root}.
     */
    @Test
    public void internalFilesRoot_isShareable() {
        assertNotNull("getAppFilesDir falls back here, so it has to be a declared FileProvider root",
                FileProvider.getUriForFile(context, authority,
                        new File(context.getFilesDir(), "probe.csv")));
    }

    /**
     * Exports are staged in an {@code exports/} subfolder, and a share hands out a URI for it.
     * Both roots declare {@code path="."}, which covers subfolders; this proves it for each.
     */
    @Test
    public void exportsSubfolder_isShareableUnderBothRoots() {
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            assertNotNull(FileProvider.getUriForFile(context, authority,
                    new File(new File(external, "exports"), "probe.edb")));
        }
        assertNotNull(FileProvider.getUriForFile(context, authority,
                new File(new File(context.getFilesDir(), "exports"), "probe.edb")));
    }
}
