package com.google.ai.edge.gallery.shizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IShizukuShellService {
    Bundle createSession(String workingDirectory);
    Bundle execute(String sessionId, String command, long timeoutMs, int maxOutputChars);
    Bundle writeBinaryFile(String targetPath, in ParcelFileDescriptor input, long maxBytes, String mimeType, long timeoutMs);
    void closeSession(String sessionId);
    void cancelSession(String sessionId);
    void closeAllSessions();
}
