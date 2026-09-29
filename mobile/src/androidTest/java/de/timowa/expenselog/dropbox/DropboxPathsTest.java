package de.timowa.expenselog.dropbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.dropbox.core.v2.files.GetMetadataError;
import com.dropbox.core.v2.files.GetMetadataErrorException;
import com.dropbox.core.v2.files.LookupError;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Only a genuine "not found" counts as a missing file. docs/history/RELIABILITY_PLAN.md, Step 6 (F20). */
@RunWith(AndroidJUnit4.class)
public class DropboxPathsTest {

    private static GetMetadataErrorException error(LookupError lookup) {
        return new GetMetadataErrorException("2/files/get_metadata", "req", null,
                GetMetadataError.path(lookup));
    }

    @Test
    public void notFound_isAMissingFile() {
        assertTrue(DropboxPaths.isNotFound(error(LookupError.NOT_FOUND)));
    }

    @Test
    public void everyOtherLookupFailure_isNot() {
        assertFalse(DropboxPaths.isNotFound(error(LookupError.NOT_FILE)));
        assertFalse(DropboxPaths.isNotFound(error(LookupError.RESTRICTED_CONTENT)));
        assertFalse(DropboxPaths.isNotFound(error(LookupError.malformedPath())));
        assertFalse(DropboxPaths.isNotFound(error(LookupError.OTHER)));
    }
}
