package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * The Dropbox credential's store, which replaced the deprecated {@code security-crypto} library.
 *
 * <p>Two properties matter beyond "it round-trips". The value on disk must not be the plaintext,
 * or the move would have been a downgrade. And a value that cannot be decrypted must read as
 * *no credential* rather than throw: this is read at every launch and on every resume that checks
 * for a login, and an exception there would crash the app instead of asking the user to log in
 * again — the same shape of bug as the declined-login loop in {@code DropBoxHelper}.
 */
@RunWith(AndroidJUnit4.class)
public class SecretStoreTest {

    private static final String KEY = "secret_store_test_key";
    private static final String PREFS_FILE = "dropbox_credential";

    private Context context;
    private SecretStore store;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        store = new SecretStore(context);
        store.remove(KEY);
    }

    @After
    public void tearDown() {
        store.remove(KEY);
    }

    @Test
    public void value_roundTrips() {
        String credential = "{\"access_token\":\"sl.ABC-123\",\"expires_at\":1789786202334}";
        assertTrue(store.put(KEY, credential));
        assertEquals(credential, store.get(KEY));
    }

    /** A fresh instance reads what another wrote: the key lives in the Keystore, not in memory. */
    @Test
    public void anotherInstance_readsTheSameValue() {
        store.put(KEY, "written by one instance");
        assertEquals("written by one instance", new SecretStore(context).get(KEY));
    }

    @Test
    public void missingValue_isNull() {
        assertNull(store.get("a key that was never written"));
    }

    @Test
    public void removedValue_isNull() {
        store.put(KEY, "to be removed");
        store.remove(KEY);
        assertNull(store.get(KEY));
    }

    /** What lands on disk is ciphertext, not the credential. */
    @Test
    public void storedForm_isNotThePlaintext() {
        String credential = "sl.this-must-not-appear-on-disk";
        store.put(KEY, credential);
        String onDisk = prefs().getString(KEY, null);
        assertNotEquals(credential, onDisk);
        assertFalse("the plaintext is readable on disk", String.valueOf(onDisk).contains(credential));
    }

    /** Two writes of the same value differ, because each gets its own IV. */
    @Test
    public void eachWrite_usesItsOwnIv() {
        store.put(KEY, "same value");
        String first = prefs().getString(KEY, null);
        store.put(KEY, "same value");
        assertNotEquals(first, prefs().getString(KEY, null));
    }

    /** A tampered value reads as no credential rather than throwing. */
    @Test
    public void tamperedValue_readsAsMissing() {
        store.put(KEY, "a credential");
        String onDisk = prefs().getString(KEY, null);
        // Flip the last character, which lands inside the GCM tag.
        char last = onDisk.charAt(onDisk.length() - 1);
        String tampered = onDisk.substring(0, onDisk.length() - 1) + (last == 'A' ? 'B' : 'A');
        prefs().edit().putString(KEY, tampered).commit();

        assertNull(store.get(KEY));
    }

    /** Rubbish in the preference, rather than ciphertext, is also just "no credential". */
    @Test
    public void unparseableValue_readsAsMissing() {
        prefs().edit().putString(KEY, "not base64 at all !!").commit();
        assertNull(store.get(KEY));
        prefs().edit().putString(KEY, "AAAA").commit();
        assertNull(store.get(KEY));
    }

    /** The old library's file is deleted, and doing it twice is not an error. */
    @Test
    public void legacyStore_isDiscarded() throws Exception {
        File sharedPrefs = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        //noinspection ResultOfMethodCallIgnored
        sharedPrefs.mkdirs();
        File legacy = new File(sharedPrefs, "dropbox_secure_prefs.xml");
        //noinspection ResultOfMethodCallIgnored
        legacy.createNewFile();
        assertTrue(legacy.exists());

        store.discardLegacyStore();
        assertFalse("the old encrypted preference file is still there", legacy.exists());

        // Again, with nothing to delete.
        store.discardLegacyStore();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }
}
