package com.turbodrop.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;

public class FolderScanner {

    public interface ScanCallback {
        void onProgress(int filesFound, long totalBytes);
        void onComplete(List<FileItem> items, long totalBytes);
    }

    public static void scanTreeAsync(ContentResolver cr, Uri treeUri, ScanCallback callback) {
        new Thread(() -> {
            List<FileItem> items = new ArrayList<>();
            try {
                String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
                String rootDirName = getDocName(cr, DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId));
                if (rootDirName == null || rootDirName.isEmpty()) {
                    rootDirName = "Folder";
                }

                scanDirectory(cr, treeUri, treeDocId, rootDirName, items, callback);
            } catch (Exception ignored) {}

            long totalBytes = 0;
            for (FileItem item : items) {
                totalBytes += item.filesize;
            }
            callback.onComplete(items, totalBytes);
        }, "FolderScannerThread").start();
    }

    private static void scanDirectory(ContentResolver cr, Uri treeUri, String parentDocId, String currentPath,
                                      List<FileItem> items, ScanCallback callback) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] projection = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };

        Cursor cursor = null;
        try {
            cursor = cr.query(childrenUri, projection, null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    long size = cursor.getLong(3);

                    if (name == null) name = "file";

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        // Subdirectory: recursive scan
                        scanDirectory(cr, treeUri, docId, currentPath + "/" + name, items, callback);
                    } else {
                        // File
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        String relPath = currentPath + "/" + name;
                        items.add(new FileItem(fileUri, name, relPath, size));

                        if (items.size() % 10 == 0) {
                            callback.onProgress(items.size(), 0);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static String getDocName(ContentResolver cr, Uri docUri) {
        Cursor c = null;
        try {
            c = cr.query(docUri, new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null);
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }
}
