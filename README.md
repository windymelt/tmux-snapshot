# tmux-snapshot

Save and restore your tmux working environment — built for parallel [Claude Code](https://claude.com/claude-code) sessions.

When you run several Claude Code sessions across tmux windows — one per git worktree, say — an unexpected shutdown (a drained battery, a kernel panic) wipes all of it: the windows, the directory each was in, and the running Claude conversations. Rebuilding that by hand is tedious. `tmux-snapshot` periodically saves your tmux layout and restores it with a single command, including resuming the exact Claude Code conversation each pane was in, by session ID.

## How it works

- **`dump`** serializes your tmux sessions — windows, panes, working directories, layout, and the command running in each pane — to JSON.
- A **systemd user timer** runs `dump` on an interval, so the snapshot stays current.
- **`restore`** rebuilds your sessions from the latest snapshot: for each saved session it recreates windows and panes in their original order and directories, reapplies pane layouts, and for each pane that was running Claude Code and has a recorded session ID, resumes that exact conversation with `claude --resume <session-id>`.
- **`list`** inspects your current tmux state — windows, panes, and any active Claude Code sessions or agents — without touching the snapshot.

Every session is saved and restored under its original name. See [Design contract](#design-contract).

## Highlights

- **Single self-contained native binary** (Scala Native, AOT-compiled) — no JVM, no runtime dependencies, and fast startup that suits a frequent timer.
- **Safe to re-run** — `restore` skips any session that already exists, so it never duplicates windows if you run it twice or invoke it from inside a live session.
- **Robust targeting via tmux's immutable pane/window IDs** (`%N` / `@N`) rather than indexes, so restoration stays correct even when window numbering differs from the saved state.
- **Backups are never clobbered** — `dump` will not overwrite a good snapshot with an empty one.
- **The exact conversation a pane was having resumes on restore**, by session ID, rather than whatever conversation happens to be latest for that directory.
- **Read-only inspection of Claude Code sessions and agents** via `list` — see which panes are running Claude, what their session state is, and which agents are active, without modifying your snapshot.

## Requirements

- [tmux](https://github.com/tmux/tmux)
- git
- [Claude Code](https://claude.com/claude-code) (`claude`), version 2.1.223 or later — for conversation resume by session ID (`claude --resume <session-id>`, which resolves IDs across projects starting from that version)
- To build from source: [sbt](https://www.scala-sbt.org/) 2.x, JDK 17+, and a Clang/LLVM toolchain (a Scala Native requirement); not required if you use a prebuilt binary from [Releases](https://github.com/windymelt/tmux-snapshot/releases)

## Installation

### Getting a prebuilt binary

Releases are available for Linux on `x86_64` and `aarch64`. Download from [Releases](https://github.com/windymelt/tmux-snapshot/releases).

**Requirements:** glibc 2.35 or later. This is available on Ubuntu 22.04 and later, and Debian 12 and later.

To download, verify, and install:

```sh
version=0.1.0
target=x86_64-linux  # Change to aarch64-linux if needed

# Download the binary with its original name
curl -LO https://github.com/windymelt/tmux-snapshot/releases/download/v${version}/tmux-snapshot-${version}-${target}

# Download and verify checksums
curl -L https://github.com/windymelt/tmux-snapshot/releases/download/v${version}/checksums.txt -o checksums.txt
# --ignore-missing is needed because checksums.txt contains entries for both targets,
# but you may have downloaded only one
sha256sum --check --ignore-missing checksums.txt

# Verify provenance attestation
gh attestation verify tmux-snapshot-${version}-${target} --repo windymelt/tmux-snapshot

# Install the binary
chmod +x tmux-snapshot-${version}-${target}
mv tmux-snapshot-${version}-${target} ~/.local/bin/tmux-snapshot
```

### Build from source

Requirements: [sbt](https://www.scala-sbt.org/) 2.x, JDK 17+, and a Clang/LLVM toolchain (a Scala Native requirement).

Build and install:

```sh
sbt nativeLink
cp target/out/native0.5/scala-3.3.8/tmux-snapshot/tmux-snapshot ~/.local/bin/
```

Alternatively, you can use the convenience script at the repository root:

```sh
./build.sh
```

This runs `sbt nativeLink` and copies the binary to `./tmux-snapshot` in the current directory.

The first build compiles LLVM-linked native code and takes a few minutes; subsequent builds are incremental.

### systemd user units

Create the directory and install the systemd user units:

```sh
mkdir -p ~/.config/systemd/user

cat > ~/.config/systemd/user/tmux-snapshot.service <<'EOF'
[Unit]
Description=Save tmux/worktree snapshot

[Service]
Type=oneshot
ExecStart=%h/.local/bin/tmux-snapshot dump
Environment=PATH=/usr/local/bin:/usr/bin:/bin
EOF

cat > ~/.config/systemd/user/tmux-snapshot.timer <<'EOF'
[Unit]
Description=Periodic tmux/worktree snapshot

[Timer]
OnBootSec=2min
OnUnitActiveSec=5min
Persistent=true

[Install]
WantedBy=timers.target
EOF
```

The `ExecStart` directive points to `~/.local/bin/tmux-snapshot`, which matches the installation path shown above. If you installed the binary to a different location, edit `ExecStart` accordingly. If you have cloned the repository, you can alternatively copy the unit files directly: `cp systemd/tmux-snapshot.service systemd/tmux-snapshot.timer ~/.config/systemd/user/`.

Enable and start the timer:

```sh
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

`restore` recreates each saved session (as a detached session) and resumes the conversation each Claude pane was holding. It prints one line per Claude pane saying what it sent, or why it sent nothing. You can also run `dump` / `restore` manually at any time.

### Checking the version

Check the binary's version with `--version`:

```sh
tmux-snapshot --version
```

For prebuilt binaries downloaded from [Releases](https://github.com/windymelt/tmux-snapshot/releases), the output includes the release tag, e.g.:

```
tmux-snapshot 1.2.3
```

When you build from source (with `sbt nativeLink` or `./build.sh`), the output is the placeholder below unless you explicitly set the `TMUX_SNAPSHOT_VERSION` environment variable at build time:

```
tmux-snapshot 0.0.0-SNAPSHOT
```

This makes it easy to distinguish self-built binaries from officially released ones and prevents an untagged source build from impersonating a release version.

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

### Listing what is running

The `list` command is a **read-only** subcommand that inspects your current tmux state without reading or writing the snapshot. It reports all windows, panes, git information, and any active Claude Code sessions or agents.

```sh
tmux-snapshot list                 # windows of the current session
tmux-snapshot list --json          # the same content as JSON
tmux-snapshot list --session work  # an explicit session
tmux-snapshot list --all           # every session
```

The output includes:

- **Window and pane topology** — window indexes, window names, and the working directory of each pane.
- **Git information** — the branch at that pane's working directory, and (if that pane's directory is a linked worktree) the worktree name and main repository name.
- **Claude Code session state** — if a pane is running Claude, the session name, state (`idle` / `busy` / `waiting`), the first 8 characters of the session ID, how long it has been running, and an excerpt from the first user message in that conversation.
- **Agent information** — if a pane is an agent pane, the agent's name, agent type, model, and team name.

Important behaviors:

- **Current session is determined by the calling pane.** `list` resolves the current session from `$TMUX_PANE`, so the session you operate on is the one that contains the pane you ran the command from — not whichever session happens to be active in your tmux client.
- **If you run `list` outside tmux without `--session` or `--all`, it fails.** A missing tmux server is also a failure. This contrasts with `dump`, which is silent — because `dump` protects a snapshot, but `list` has nothing to protect, so a failure is worth reporting. Exit code is 1 in both cases.
- **Supplementary information degrades gracefully.** If a pane's directory is not inside a git repository, or if Claude Code information cannot be fetched, `list` omits those lines for that pane and continues — the command always succeeds if it can read tmux state at all.
- **The window containing the pane you ran the command from is marked.** The `← current` annotation appears next to that window.
- **Long paths are truncated to a fixed width.** Paths longer than 52 characters are shortened by replacing the left side with `…` and preserving the right end — keeping the worktree name or final directory visible for identification.

Here is a human-readable example:

```
session 0  (10 windows, 18 panes, attached)

 6: zsh                                                              ← current
    1  ~/src/github.com/windymelt/tmux-snapshot                claude
       git  main
       cc   tmux-snapshot-cc  busy  df6999e4  2h12m  "Let's think about this together…"
    2  ~/src/github.com/windymelt/tmux-snapshot                2.1.222
       git  main
       cc   agent HQ-list-windows (Headquarter/opus)  team session-df6999e4

 9: claude
    1  …/forum/.claude/worktrees/bump-gemini-25-into-3x         claude
       git  worktree-bump-gemini-25-into-3x  [worktree: bump-gemini-25-into-3x → forum]
```

When you pass `--json`, `list` returns the same information in machine-readable JSON format. Supplementary fields that could not be retrieved (git info, Claude Code state, agent details, session attachment state) appear as `null` in the JSON output.

## Design contract

- **All sessions are saved by default.** `dump` captures every session on the tmux server (`tmux list-panes -a`). Scope it to one session with `--session <name>` (see [Working with a single session](#working-with-a-single-session)).
- **`dump` never destroys a good snapshot.** If tmux is not running (e.g. just after a reboot, before you restore), `dump` does nothing and leaves the last good `state.json` intact.
- **`restore` is decided per session by name.** For each saved session, if a session with that name already exists, `restore` warns and leaves it untouched; otherwise it is rebuilt. This makes `restore` safe to run more than once and from inside a live tmux session.
- **Immutable IDs.** Windows and panes are targeted by tmux IDs captured at creation, never by index.
- **Missing directories degrade gracefully, but Claude is not resumed there.** If a pane's saved directory no longer exists, `restore` falls back to the git repository root, then to `$HOME`, so a window is always created — but no `claude` command is sent to that pane, because resuming the saved conversation in a different directory would misattribute it. The git-repository-root fallback only helps when the pane's own subdirectory vanished while the rest of the repository is still there; it cannot recover a removed linked worktree, because the git root recorded for a worktree pane is the worktree's own path, which disappears along with the worktree.
- **Snapshot version determines resume behavior.** Snapshots written before session-ID tracking are `version: 0` and carry no per-pane session ID, so `restore` falls back to `claude -c` for panes that were running Claude Code. `version: 1` snapshots carry a `claudeSessionId` per pane when one could be determined uniquely; `restore` sends `claude --resume <session-id>` for those panes and sends nothing to a Claude pane with no recorded ID, rather than guessing with `claude -c`.

## Limitations

- Directories and git worktrees are not recreated — `restore` assumes the saved paths still exist (and falls back gracefully when they do not).
- Resuming a pane's program is limited to Claude Code, and only when a session ID was recorded for that pane; other running programs are not relaunched.
- Cross-project session resolution only succeeds when exactly one other project holds a transcript for that session ID. If that condition is not met, `claude --resume <session-id>` prints `No conversation found with session ID: <id>` and exits 1, leaving the pane at a shell prompt — `restore` does not fall back to an interactive picker. This failure is visible on the pane rather than silent, which is the intended trade-off against resuming the wrong conversation.

## License

[MIT](LICENSE)
