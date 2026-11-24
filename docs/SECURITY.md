# Security notes

NativeForge compiles and runs code on the device. This page lists what the IDE
protects against and what it cannot.

## What the IDE guards against

| Risk | Protection |
| --- | --- |
| A cloned repository plants symlinks to private app files (toolchain, other projects) | JGit is told the file system has no symlinks, so links are checked out as plain files; leftover links are removed after a clone |
| Git hooks or config run code on clone, commit or pull | No hooks (`NoHooksFs`), no system/user git config, no submodules |
| Non-HTTPS remotes (`file://`, `ssh://`, `ext::`) or credentials in a URL | Clone, pull and push accept `https://` without user info only |
| Tokens leaked to disk | Credentials live in memory for the session only and are never written |
| Deleting a project or folder follows a link out of it | `SafeFiles.deleteTree` never follows links and only deletes inside the workspace |
| Package archives write through a planted link or hard-link outside the folder | `ArchiveExtractor` removes an existing link before writing and rejects outside targets; archives are verified with SHA-256 over HTTPS |
| Exported APKs pick up private files through links in `assets/` | Only regular files are bundled |
| Build settings point outside the project (`../../`) | Program file, source folder and icon paths are resolved inside the project |
| The editor opens a link to a private file | Links that leave the project are refused; binary and very large files are not opened |
| Other apps start IDE screens or read files | Every component except the launcher is `exported=false`; the FileProvider only shares `exports/`; `allowBackup=false` |

## Remote access (browser IDE)

Remote access (Home › cast icon) lets a browser on a computer use the IDE: the
phone runs an HTTPS server (`core/remote`) inside a foreground service. It is
off until the user starts it and stops itself after an idle time (30 minutes by
default) or from its notification.

| Risk | Protection |
| --- | --- |
| Someone on the network (or the internet) connects | Only peers on loopback, private LAN ranges, link-local, IPv6 ULA and 100.64/10 (Tailscale) are accepted, before anything is read (`NetworkPolicy.isLocalPeer`); a phone with a public address is not reachable |
| Eavesdropping or tampering on the Wi-Fi | HTTPS only (TLS 1.2/1.3) with an ECDSA P-256 key made on the device (`TlsIdentity`, `files/remote/tls.p12`). No CA signs it: the app shows its SHA-256 fingerprint for the user to compare with the browser's warning page |
| A browser connects without the user's consent | Every API call and WebSocket needs a session from pairing: an 8-character one-time code shown on the phone (~40 bits, 5 minutes, destroyed after 5 wrong tries) is exchanged for a 256-bit token (`AccessControl`) |
| Session theft from disk or scripts | Only the SHA-256 of each token is stored (`files/remote/browsers.json`); the cookie is `__Host-`, `HttpOnly`, `Secure`, `SameSite=Strict`; sessions lapse after 30 days unused and can be revoked one by one or all at once, which also closes their connections |
| A web page the user visits drives the IDE (CSRF, cross-site WebSocket, DNS rebinding) | State-changing requests and WebSocket upgrades need `Origin` equal to the server's own origin; the `Host` header must be an IP literal, `localhost`, `*.local` or `*.ts.net`; `SameSite=Strict` cookies |
| Script injection into the page | Strict CSP (`script-src 'self'` plus the hash of the one inline import map, no `eval`), `frame-ancestors 'none'`, `nosniff`, `no-referrer`; all scripts are bundled in the APK (no CDN), pinned by SHA-256 in `core/remote/web/vendor.sha256` |
| File requests reach private app files (keys, tokens, other apps' data) | File API paths are relative to one project folder; absolute paths, `..`, NUL and anything whose canonical path (links followed) leaves the project are refused (`ProjectFiles`) |
| Malformed or huge requests | Request head ≤ 16 KB, body ≤ 32 MB, no chunked bodies, duplicate `Host`/`Content-Length` refused, WebSocket messages ≤ 8 MB with masking enforced, at most 48 connections |

A paired browser can do everything the app can, including opening a terminal,
so it is as trusted as the phone's own screen. Pair only your own computers.
To work away from home, use a VPN such as Tailscale or WireGuard on both
devices; never forward the port on a router.

## What it cannot protect against

Building and running a project executes its code with the IDE's own
permissions: CMake configure scripts, Makefile recipes, `build.ninja` commands
and the program itself can read and change everything the IDE can (projects,
the toolchain, git settings). Android's sandbox still keeps them away from
other apps and from system files. **Only build code you trust**, and read a
cloned project's build scripts first.
