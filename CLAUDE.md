# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and run

```sh
# Type-check and compile
sbt compile

# Build a native binary
sbt nativeLink

# Install the binary
cp target/scala-3.3.8/tmux-snapshot-out ~/.local/bin/tmux-snapshot
```

The first build compiles and links LLVM-generated native code and takes a few minutes; subsequent builds are incremental and fast.

## Architecture

All logic lives in a single file, `src/main/scala/tmux-snapshot.scala`. Build settings are declared in `build.sbt` using [sbt-scala-native](https://scala-native.org/en/stable/user/sbt.html).

### Data flow

```
dump:
  tmux list-panes -a -F <format>
    → buildWindows()   parses tab-separated text into a List[WindowState]
    → gitInfo()        resolves git root / branch / worktree flag per pane path
    → Snapshot JSON    written to ~/.local/share/tmux-snapshot/state.json

restore:
  read state.json
    → createWindow()   recreates each session / window / pane via tmux commands
    → select-layout    reapplies the saved pane layout
    → send "claude -c" only to panes whose runningCommand was "claude"

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
- **restore idempotency**: `tmux has-session` is checked before recreating each session. Existing sessions are skipped, so running `restore` from inside a live tmux session never duplicates windows.
- **`--session` merge**: `dump --session <name>` preserves all other sessions in the snapshot by filtering them out and appending only the newly captured session (`filterNot(_.session == s) ++ captured`).
- **`tmux` intercept before scopt**: `Main.main` handles `tmux` as the first argument before `OParser.parse`, so passthrough arguments never reach the dump/restore parser (which rejects unknown args). dump/restore parsing is unaffected.
- **exec after terminal restore**: in the confirm path, `restore` and `execTmux` run only after `SyncPromptsBuilder.use` returns, because `use` restores the terminal from raw to cooked mode in its `finally`. Calling exec inside the `use` lambda would leave tmux to inherit a raw terminal and misbehave on attach.
- **no alias recursion**: `execTmux` calls `execvp("tmux", …)`, which performs a PATH lookup and invokes the real tmux binary. Shell aliases are not inherited across `execvp`, so `alias tmux='tmux-snapshot tmux'` does not recurse.

### Dependencies

- **Circe**: JSON serialization for `Snapshot` / `WindowState` / `PaneState` via `derives Codec.AsObject`
- **scopt**: CLI option parsing via `OParser`
- **scala-java-time**: shim providing `java.time.Instant` for Scala Native
- **cue4s** (`tech.neander`): synchronous terminal prompt (`SyncPromptsBuilder`) for the `tmux` confirm dialog
- **Scala Native posixlib**: `unistd.execvp` for process image replacement and `unistd.isatty` for the TTY check in the `tmux` path
