package de.timowa.expenselog.dropbox;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.GetMetadataErrorException;
import com.dropbox.core.v2.files.Metadata;

/**
 * Finds a Dropbox file by its path.
 *
 * <p>Replaces {@code files().searchV2(name)}, which the sync check and the auto-backups used to
 * decide whether a file exists and what its revision is. Search is the wrong tool: its index is
 * updated eventually, so a file uploaded moments ago can be missing (and the sync check then
 * treated the remote as absent and uploaded over it); it matches by relevance, not by exact path,
 * so its first result need not be the file asked for; and it can return a folder, which the
 * callers cast to {@code FileMetadata}. docs/history/RELIABILITY_PLAN.md, Step 6 (F20).
 */
final class DropboxPaths {

    private DropboxPaths() {
    }

    /**
     * The file at {@code path}, or null if nothing exists there.
     *
     * @throws DbxException for any other failure, including a folder at that path
     */
    static FileMetadata fileAt(DbxClientV2 client, String path) throws DbxException {
        Metadata metadata;
        try {
            metadata = client.files().getMetadata(path);
        } catch (GetMetadataErrorException e) {
            if (isNotFound(e))
                return null;
            throw e;
        }
        if (!(metadata instanceof FileMetadata))
            throw new DbxException("not a file on Dropbox: " + path);
        return (FileMetadata) metadata;
    }

    /** Whether a metadata lookup failed only because nothing exists at the path. */
    static boolean isNotFound(GetMetadataErrorException e) {
        return e.errorValue != null && e.errorValue.isPath() && e.errorValue.getPathValue().isNotFound();
    }
}
