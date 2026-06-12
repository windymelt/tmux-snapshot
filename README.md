# tmux-snapshot

Save and restore your tmux working environment — built for parallel [Claude Code](https://claude.com/claude-code) sessions.

When you run several Claude Code sessions across tmux windows — one per git worktree, say — an unexpected shutdown (a drained battery, a kernel panic) wipes all of it: the windows, the directory each was in, and the running Claude conversations. Rebuilding that by hand is tedious. `tmux-snapshot` periodically saves your tmux layout and restores it with a single command, including resuming each Claude Code conversation via `claude -c`.

## How it works

- **`dump`** serializes your tmux sessions — windows, panes, working directories, layout, and the command running in each pane — to JSON.
- A **systemd user timer** runs `dump` on an interval, so the snapshot stays current.
- **`restore`** rebuilds your sessions from the latest snapshot: for each saved session it recreates windows and panes in their original order and directories, reapplies pane layouts, and sends `claude -c` to any pane that was running Claude Code so the conversation resumes where it left off.

Every session is saved and restored under its original name. See [Design contract](#design-contract).

## Highlights

- **Single self-contained native binary** (Scala Native, AOT-compiled) — no JVM, no runtime dependencies, and fast startup that suits a frequent timer.
- **Safe to re-run** — `restore` skips any session that already exists, so it never duplicates windows if you run it twice or invoke it from inside a live session.
- **Robust targeting via tmux's immutable pane/window IDs** (`%N` / `@N`) rather than indexes, so restoration stays correct even when window numbering differs from the saved state.
- **Backups are never clobbered** — `dump` will not overwrite a good snapshot with an empty one.
- **Claude Code conversations resume automatically** on restore.

## Requirements

- [tmux](https://github.com/tmux/tmux)
- git
- [Claude Code](https://claude.com/claude-code) (`claude`) — for conversation resume
- To build: [scala-cli](https://scala-cli.virtuslab.org/) and an LLVM/Clang toolchain (a Scala Native requirement)

## Installation

Build the binary:

```sh
scala-cli package --native tmux-snapshot.scala -o ~/.local/bin/tmux-snapshot
```

The first build compiles LLVM-linked native code and takes a little while; afterwards you just run the binary.

Install the systemd user units:

```sh
cp systemd/tmux-snapshot.service systemd/tmux-snapshot.timer ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now tmux-snapshot.timer
```

The timer first fires 2 minutes after boot, then every 5 minutes. systemd user services do not read your shell profile, so edit `Environment=PATH=` in the service unit if `tmux` / `git` / `claude` live somewhere other than `/usr/local/bin:/usr/bin:/bin`.

## Usage

Just use tmux as you normally would. The timer saves all of your running sessions automatically; the snapshot lives at `~/.local/share/tmux-snapshot/state.json`.

After a reboot, from any fresh shell:

```sh
tmux-snapshot restore
tmux attach            # or: tmux attach -t <session-name>
```

`restore` recreates each saved session (as a detached session) and resumes each Claude conversation. You can also run `dump` / `restore` manually at any time.

### Choosing the snapshot file

By default the snapshot is read from and written to `~/.local/share/tmux-snapshot/state.json`. Pass `--state <path>` to use a different file:

```sh
tmux-snapshot --state /tmp/test-state.json dump
tmux-snapshot --state /tmp/test-state.json restore
```

This is convenient for trying the tool out without touching the snapshot your timer maintains.

### Working with a single session

Pass `--session <name>` to operate on just one session:

```sh
tmux-snapshot --session work dump      # snapshot only the "work" session
tmux-snapshot --session work restore   # restore only the "work" session
```

- `dump --session <name>` captures only that session and **merges** it into the snapshot: the entries for other sessions already in the file are preserved, and only the named session is replaced. It never clobbers the rest of the snapshot.
- `restore --session <name>` restores only that session and touches no other. If the snapshot does not contain it, the command fails without changing anything.

Without `--session`, both commands operate on every session, as described in the [Design contract](#design-contract).

## Design contract

- **All sessions are saved by default.** `dump` captures every session on the tmux server (`tmux list-panes -a`). Scope it to one session with `--session <name>` (see [Working with a single session](#working-with-a-single-session)).
- **`dump` never destroys a good snapshot.** If tmux is not running (e.g. just after a reboot, before you restore), `dump` does nothing and leaves the last good `state.json` intact.
- **`restore` is decided per session by name.** For each saved session, if a session with that name already exists, `restore` warns and leaves it untouched; otherwise it is rebuilt. This makes `restore` safe to run more than once and from inside a live tmux session.
- **Immutable IDs.** Windows and panes are targeted by tmux IDs captured at creation, never by index.
- **Missing directories degrade gracefully.** If a pane's saved directory no longer exists, `restore` falls back to the git repository root, then to `$HOME`, so a window is always created.

## Limitations

- Directories and git worktrees are not recreated — `restore` assumes the saved paths still exist (and falls back gracefully when they do not).
- Resuming a pane's program is limited to Claude Code (`claude -c`); other running programs are not relaunched.

## License

[MIT](LICENSE)
