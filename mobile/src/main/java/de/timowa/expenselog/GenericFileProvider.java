package de.timowa.expenselog;

import androidx.core.content.FileProvider;

/**
 * Generic file provider to make sure our FileProvider
 * doesn't conflict with FileProviders declared in imported dependencies as described here
 */

public class GenericFileProvider extends FileProvider {

}
