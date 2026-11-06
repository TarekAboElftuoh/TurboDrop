package com.turbodrop.app;

import android.net.Uri;

public class FileItem {
    public final Uri uri;
    public final String filename;
    public final String relativePath;
    public final long filesize;

    public FileItem(Uri uri, String filename, String relativePath, long filesize) {
        this.uri = uri;
        this.filename = filename;
        this.relativePath = (relativePath == null || relativePath.isEmpty()) ? filename : relativePath;
        this.filesize = filesize;
    }
}
