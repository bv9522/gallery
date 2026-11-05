# Local MCP build

This fork adds a separate **Local MCP** product flavor while preserving the standard Edge Gallery flavor.

## Build on Windows

Open the Gradle project in Android Studio from `Android/src`, then select the `localMcp` flavor and a Debug build variant.

From PowerShell, run these commands:

```powershell
Set-Location .\Android\src
.\gradlew.bat :app:testLocalMcpDebugUnitTest
.\gradlew.bat :app:assembleLocalMcpDebug
```

The APK is produced under:

```
Android/src/app/build/outputs/apk/localMcp/debug/
```

The Local MCP app uses the application ID `com.google.aiedge.gallery.localmcp` and is labeled **Edge Gallery (Local MCP)**, so it can be installed alongside the Play Store version. The standard flavor retains the original application ID.

## Connecting to a phone-local MCP server

When the MCP server is running on the same Android device, add its Streamable HTTP endpoint in Edge Gallery:

```
http://127.0.0.1:8765/mcp
```

Use the actual port and path configured by your local MCP server.

Only HTTPS endpoints are accepted for remote servers. HTTP is accepted only for `localhost` and `127.0.0.1`. The Local MCP flavor opts into cleartext traffic only for those loopback hosts; the standard flavor does not. URL validation is applied both when adding a server and when restoring saved server configurations. Redirects are disabled for MCP HTTP requests to prevent a loopback or HTTPS endpoint from redirecting to an unvalidated cleartext destination.

Keep per-tool approval enabled for shell-capable MCP servers. This build does not change the default tool approval behavior.
