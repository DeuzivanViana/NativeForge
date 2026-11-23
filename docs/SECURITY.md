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

## What it cannot protect against

Building and running a project executes its code with the IDE's own
permissions: CMake configure scripts, Makefile recipes, `build.ninja` commands
and the program itself can read and change everything the IDE can (projects,
the toolchain, git settings). Android's sandbox still keeps them away from
other apps and from system files. **Only build code you trust**, and read a
cloned project's build scripts first.
