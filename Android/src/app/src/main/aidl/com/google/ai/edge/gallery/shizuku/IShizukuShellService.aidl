package com.google.ai.edge.gallery.shizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IShizukuShellService {
    Bundle createSession(String workingDirectory);
    Bundle execute(String sessionId, String command, long timeoutMs, int maxOutputChars);
    void closeSession(String sessionId);
    void cancelSession(String sessionId);
    void closeAllSessions();
    Bundle writeBinaryFile(String destinationPath, in ParcelFileDescriptor source, long maxBytes, long timeoutMs, boolean overwrite, String expectedSha256);
}
