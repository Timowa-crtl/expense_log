package de.timowa.expenselog;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * One small secret, encrypted with a key that never leaves the device's Keystore.
 *
 * <p>Holds the Dropbox credential. It used to live in {@code EncryptedSharedPreferences} from
 * {@code androidx.security:security-crypto}, which Google deprecated in full at 1.1.0 — every API,
 * no successor library, in favour of "existing platform APIs and direct use of Android Keystore".
 * That is what this is: an AES-256-GCM key in {@code AndroidKeyStore}, a fresh random IV per write,
 * and the IV and ciphertext stored Base64-encoded in ordinary {@link SharedPreferences}.
 *
 * <p><b>Nothing is migrated from the old store.</b> The deprecated library is gone, so the old file
 * cannot be read; {@link #discardLegacyStore} deletes it and its master key instead, and the user
 * logs into Dropbox once more. That was the maintainer's call, made when the installed base was two
 * phones and two emulators.
 *
 * <p>A read that cannot be decrypted returns null rather than throwing: a corrupt or
 * foreign-keyed value must read as "no credential", which starts a login, not as a crash on a
 * path that runs at every launch.
 */
final class SecretStore {

    private static final String TAG = "SecretStore";

    /** Plain preferences: the values in them are ciphertext. */
    private static final String PREFS_FILE = "dropbox_credential";

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "expenselog_secret_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    /** The old library's preference file and the master key it used. */
    private static final String LEGACY_PREFS_FILE = "dropbox_secure_prefs";
    private static final String LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_";

    /** Set once the legacy master key has been dealt with, so every later helper skips the IPC. */
    private static volatile boolean legacyDiscarded;

    private final Context context;

    SecretStore(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The stored value for {@code key}, or null if there is none or it cannot be decrypted. */
    String get(String key) {
        String stored = prefs().getString(key, null);
        if (stored == null) return null;
        try {
            byte[] blob = Base64.decode(stored, Base64.NO_WRAP);
            if (blob.length <= IV_BYTES) return null;
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            // Tampered, truncated, or written under a key this install no longer has: read it as
            // "no credential", which asks the user to log in again.
            Log.w(TAG, "could not decrypt " + key, e);
            return null;
        }
    }

    /** Stores {@code value} under {@code key}, encrypted. Returns false if it could not. */
    boolean put(String key, String value) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(cipherText, 0, blob, iv.length, cipherText.length);
            prefs().edit()
                    .putString(key, Base64.encodeToString(blob, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.w(TAG, "could not encrypt " + key, e);
            return false;
        }
    }

    /** Removes the stored value for {@code key}. */
    void remove(String key) {
        prefs().edit().remove(key).apply();
    }

    /**
     * Deletes the {@code security-crypto} store and its master key, once.
     *
     * <p>Its contents cannot be read any more — the library that could is gone — so leaving the
     * file behind would leave an undecryptable credential and its Keystore key on the device for
     * good.
     *
     * <p>The <em>Keystore</em> half runs once per process, not once per caller: {@code
     * DropBoxHelper} is constructed per activity, and the lookup below is IPC on the main thread.
     * Doing it on every activity creation is the same cost the credential store was cached to
     * avoid in the first place. The file half is a {@code stat} and is not worth guarding -- and
     * guarding it made the behaviour depend on whether anything else in the process had already
     * called this, which is not something a caller can see or a test can arrange.
     */
    void discardLegacyStore() {
        File legacy = new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"),
                LEGACY_PREFS_FILE + ".xml");
        if (legacy.exists() && !legacy.delete()) {
            Log.w(TAG, "could not delete the old encrypted preference file");
        }
        if (legacyDiscarded) return;
        legacyDiscarded = true;
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(LEGACY_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS);
            }
        } catch (GeneralSecurityException | java.io.IOException e) {
            Log.w(TAG, "could not delete the old master key", e);
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    /** The app's secret key, created on first use and kept in the device's Keystore. */
    private SecretKey key() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("could not open the keystore", e);
        }
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user-authentication requirement: the credential has to be readable by the
                // background sync, which runs with the screen off.
                .build());
        return generator.generateKey();
    }
}
