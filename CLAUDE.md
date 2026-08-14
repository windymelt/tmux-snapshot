# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and run

```sh
# Type-check and compile
sbt compile

# Build a native binary
sbt nativeLink

# Install the binary
cp target/out/native0.5/scala-3.3.8/tmux-snapshot/tmux-snapshot ~/.local/bin/tmux-snapshot
```

The first build compiles and links LLVM-generated native code and takes a few minutes; subsequent builds are incremental and fast.

## Architecture

All logic lives in a single file, `src/main/scala/tmux-snapshot.scala`. Build settings are declared in `build.sbt` using [sbt-scala-native](https://scala-native.org/en/stable/user/sbt.html).

### Data flow

```
dump:
  tmux list-panes -a -F <format>
    → parseListPanes()             parses tab-separated text into a List[ListRawPane] (shared with `list`)
    → claudeSessionIdsByPanePid()  attributes a Claude session id to panes that resolve to exactly one
    → buildWindows()               groups panes into a List[WindowState]
    → gitInfo()                    resolves git root / branch / worktree flag per pane path
    → Snapshot JSON                written to ~/.local/share/tmux-snapshot/state.json (version = snapshotVersion)

restore:
  read state.json
    → createWindow()   recreates each session / window / pane via tmux commands
    → select-layout    reapplies the saved pane layout
    → resumeClaude()   for panes whose runningCommand was "claude": sends "claude --resume <id>"
                         when claudeSessionId is recorded, "claude -c" only as a fallback for
                         version 0 snapshots (no ids at all), otherwise sends nothing

tmux (passthrough; intended for `alias tmux='tmux-snapshot tmux'`):
  launchTmux(argsAfter "tmux")
    1. isTmuxRunning                       → execTmux(passthrough)   (server up: no restore decision)
    2. no server, snapshot missing/corrupt → execTmux(passthrough)
    3. no server, snapshot present, non-TTY→ execTmux(passthrough)   (never prompt; default No)
    4. no server, snapshot present, TTY    → cue4s confirm (default No)
                                               Yes → restore() then execTmux(["attach"])
                                               No/interrupt → execTmux(passthrough)
```

### Key implementation constraints

- **Immutable tmux IDs**: `pane_id` (`%N`) and `window_id` (`@N`) are used for all tmux targets. Indexes are renumbered when a new session is created and diverge from saved values; immutable IDs do not.
- **Numeric session names**: tmux interprets `-t 1` as window index 1, not session name `1`. All session targets are written with a trailing colon (`"name:"`) in both `dump`'s `list-panes -t` and `createWindow`'s `new-window -t`.
- **dump safety guard**: `isTmuxRunning` is checked at the top of `dump`. If tmux is not running (e.g. right after reboot), `dump` returns immediately and leaves the last good snapshot intact.
- **shared list parser**: `dump` and `list` both build `List[ListRawPane]` via the same `listFormat` and `parseListPanes`, so the two subcommands request the same fields from tmux in the same format and cannot drift apart in how the output is parsed.
- **dump empty-snapshot guard**: `parseListPanes` degrades silently — a line that fails to parse is dropped rather than raising an error. `dump` treats a non-empty `tmux list-panes` output that parses to zero panes as a sign that `listFormat` and `parseListPanes` have diverged, and skips writing the state file rather than replacing a good snapshot with an empty one. This matters because `dump` runs unattended on a systemd user timer every 5 minutes, so a silent overwrite would go unnoticed until a restore was needed.
- **restore idempotency**: `tmux has-session` is checked before recreating each session. Existing sessions are skipped, so running `restore` from inside a live tmux session never duplicates windows.
- **`--session` merge**: `dump --session <name>` preserves all other sessions in the snapshot by filtering them out and appending only the newly captured session (`filterNot(_.session == s) ++ captured`).
- **`tmux` intercept before scopt**: `Main.main` handles `tmux` as the first argument before `OParser.parse`, so passthrough arguments never reach the dump/restore parser (which rejects unknown args). dump/restore parsing is unaffected.
- **exec after terminal restore**: in the confirm path, `restore` and `execTmux` run only after `SyncPromptsBuilder.use` returns, because `use` restores the terminal from raw to cooked mode in its `finally`. Calling exec inside the `use` lambda would leave tmux to inherit a raw terminal and misbehave on attach.
- **no alias recursion**: `execTmux` calls `execvp("tmux", …)`, which performs a PATH lookup and invokes the real tmux binary. Shell aliases are not inherited across `execvp`, so `alias tmux='tmux-snapshot tmux'` does not recurse.
- **`Snapshot.version` gates the resume fallback**: the constant `snapshotVersion` (currently 1) records whether a snapshot's panes carry per-pane `claudeSessionId`s. `resumeClaude` sends `claude --resume <id>` when a pane has one, falls back to `claude -c` only for version 0 snapshots (written before ids existed), and sends nothing for a version 1+ pane without an id, because a version 1+ pane without an id means `dump` could not attribute a single session to it, and guessing there would reintroduce the same misattribution that ids were added to fix.
- **claude is never resumed in a fallback directory**: when a pane's saved `currentPath` no longer exists, `resolveDir` falls back to `gitRoot` or `home` so window creation never fails, but `resumeClaude` checks `savedDir` separately and sends nothing in that case. The conversation belongs to the directory that is gone, not to the fallback directory.
- **`claude --resume` cross-project resolution and its limits**: resolving a session id that belongs to a different working directory than the one `claude` is started in requires Claude Code v2.1.223 or later, and only succeeds when exactly one other project holds a transcript for that id. When it does not resolve, `claude --resume <id>` prints `No conversation found with session ID: <id>` and exits 1, leaving the pane at a shell prompt rather than falling back to an interactive picker. This failure is visible on the pane itself, which is accepted as preferable to silently opening the wrong conversation.
- **`resolveDir`'s `gitRoot` fallback is narrow**: it only rescues a pane that sat in a subdirectory of a repository and lost that subdirectory alone. It does not rescue a deleted linked worktree, because `gitInfo` records `git rev-parse --show-toplevel`, which inside a linked worktree is the worktree's own path and disappears along with it; such a pane falls through to `home`.
- **version string injected at build time, never hardcoded**: the version is read from the `TMUX_SNAPSHOT_VERSION` environment variable at build time by sbt-buildinfo and embedded in the binary; it is never hardcoded in source. An empty string is treated the same as an unset variable (to handle workflows that always export the variable but may set it to an empty string), preventing an untagged build from impersonating a release version.
- **scopt `--version` output depends on `head` and `version` staying together**: scopt renders `--version` output from the parser's `head(...)` text rather than from a separate version string. The `head(...)` declaration and the `version("version")` call must always be maintained as a pair; removing `head` alone leaves `--version` printing a blank line and exiting 0, so the breakage goes undetected without a test.

### Dependencies

- **Circe**: JSON serialization for `Snapshot` / `WindowState` / `PaneState` via `derives Codec.AsObject`
- **scopt**: CLI option parsing via `OParser`
- **scala-java-time**: shim providing `java.time.Instant` for Scala Native
- **cue4s** (`tech.neander`): synchronous terminal prompt (`SyncPromptsBuilder`) for the `tmux` confirm dialog
- **Scala Native posixlib**: `unistd.execvp` for process image replacement and `unistd.isatty` for the TTY check in the `tmux` path
- **sbt-buildinfo**: generates `buildinfo.BuildInfo` module; `--version` flag reads `BuildInfo.version`
