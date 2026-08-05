import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import scala.sys.process.*
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import scopt.OParser
import scala.scalanative.unsafe.*
import scala.scalanative.posix.unistd
import cue4s.*

/** Per-pane state. runningCommand is the foreground command name at dump time. */
case class PaneState(
  paneIndex: Int,
  currentPath: String,
  runningCommand: String,
  gitRoot: Option[String],
  branch: Option[String],
  isWorktree: Boolean
) derives Codec.AsObject

/** Per-window state. windowLayout is the tmux layout string. */
case class WindowState(
  session: String,
  windowIndex: Int,
  windowName: String,
  windowLayout: String,
  panes: List[PaneState]
) derives Codec.AsObject

/** Snapshot of save time and all window states. */
case class Snapshot(
  version: Int,
  savedAt: String,
  windows: List[WindowState]
) derives Codec.AsObject

val home             = sys.env.getOrElse("HOME", System.getProperty("user.home"))
// Default save location when --state is not specified
val defaultStateFile = Paths.get(home, ".local", "share", "tmux-snapshot", "state.json")

/** Logger that discards both stdout and stderr. Used to suppress error output from tmux and git. */
val sink = ProcessLogger(_ => (), _ => ())

/** Parsed command-line config. When session is Some, dump/restore/list targets only that session.
 *  json and all are only read by list; dump and restore ignore them silently. */
case class Config(
  command: String = "",
  stateFile: java.nio.file.Path = defaultStateFile,
  session: Option[String] = None,
  json: Boolean = false,
  all: Boolean = false
)

val cliParser = {
  val builder = OParser.builder[Config]
  import builder.*
  OParser.sequence(
    programName("tmux-snapshot"),
    opt[String]("state")
      .valueName("<path>")
      .action((x, c) => c.copy(stateFile = Paths.get(x)))
      .text("path to snapshot file"),
    opt[String]("session")
      .valueName("<name>")
      .action((x, c) => c.copy(session = Some(x)))
      .text("target session name"),
    opt[Unit]("json")
      .action((_, c) => c.copy(json = true))
      .text("emit JSON (list only)"),
    opt[Unit]("all")
      .action((_, c) => c.copy(all = true))
      .text("target every session (list only)"),
    help("help").text("show this message"),
    arg[String]("<command>")
      .action((x, c) => c.copy(command = x))
      .validate(x =>
        if (x == "dump" || x == "restore" || x == "list") success
        else failure(s"command must be dump, restore or list: $x")
      )
      .text("command to run (dump, restore or list)")
  )
}

object Main {
  def main(args: Array[String]): Unit = {
    // Intercept the `tmux` passthrough subcommand before scopt parsing so it never
    // reaches the dump/restore parser, which would reject unknown arguments.
    if (args.headOption.contains("tmux")) { launchTmux(args.tail.toSeq); return }
    OParser.parse(cliParser, args, Config()) match {
      case Some(cfg) =>
        cfg.command match {
          case "dump"    => dump(cfg.stateFile, cfg.session)
          case "restore" => restore(cfg.stateFile, cfg.session)
          case "list"    => list(cfg.session, cfg.all, cfg.json)
        }
      case None => sys.exit(1)
    }
  }
}

def isTmuxRunning: Boolean = {
  Process(Seq("tmux", "list-sessions")).run(sink).exitValue() == 0
}

/** Implements the `tmux` passthrough subcommand (intended for `alias tmux='tmux-snapshot tmux'`).
 *
 *  1. tmux server already running          → pass through, no restore decision.
 *  2. no server, no usable snapshot         → pass through.
 *  3. no server, snapshot exists, non-TTY   → pass through (never prompt; default is No).
 *  4. no server, snapshot exists, TTY       → confirm; Yes restores then attaches, No passes through.
 *
 *  The confirmation defaults to No so an accidental Enter does not trigger a restore.
 *  restore is idempotent (has-session guard), so an unwanted restore is non-destructive anyway. */
def launchTmux(passthrough: Seq[String]): Unit = {
  if (isTmuxRunning) { execTmux(passthrough); return }
  readSnapshot(defaultStateFile) match {
    case None => execTmux(passthrough)
    case Some(_) =>
      if (unistd.isatty(unistd.STDIN_FILENO) != 1) {
        execTmux(passthrough)
      } else {
        // The prompt must complete and restore the terminal (SyncPromptsBuilder.use closes it in
        // a finally block) before exec. Running exec inside the use block would leave tmux to
        // inherit a raw terminal and misbehave on attach.
        val yes = new SyncPromptsBuilder()
          .use(_.confirm("No tmux server running. Restore the saved snapshot?", default = false))
          .toOption
          .getOrElse(false)
        if (yes) { restore(defaultStateFile, None); execTmux(Seq("attach")) }
        else { execTmux(passthrough) }
      }
  }
}

/** Replaces the current process with tmux via execvp, passing args after the program name.
 *  execvp performs a PATH lookup, so a shell alias that points back here does not recurse.
 *  Returns only on failure; on success the process image is replaced and control never comes back. */
def execTmux(args: Seq[String]): Unit = {
  // execvp replaces the process image and discards buffered output, so flush first.
  System.out.flush()
  System.err.flush()
  Zone.acquire { implicit z =>
    val argv = alloc[CString](args.size + 2)
    argv(0) = toCString("tmux")
    args.zipWithIndex.foreach { case (a, i) => argv(i + 1) = toCString(a) }
    argv(args.size + 1) = null
    unistd.execvp(c"tmux", argv)
  }
  System.err.println("tmux-snapshot: failed to exec tmux")
  sys.exit(127)
}

/** Runs a command and returns its stdout. Returns None if the exit code is non-zero. */
def runCapture(cmd: Seq[String]): Option[String] = {
  try { Some(Process(cmd).!!(sink)) }
  catch { case _: Throwable => None }
}

/** Reads the snapshot file. Returns None if the file does not exist or is malformed. */
def readSnapshot(stateFile: java.nio.file.Path): Option[Snapshot] = {
  if (!Files.exists(stateFile)) { None }
  else {
    val json = new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8)
    decode[Snapshot](json).toOption
  }
}

/** Captures tmux state and writes it to stateFile.
 *  When session is Some, only that session is targeted and merged into the snapshot:
 *  other sessions already in the file are preserved, preventing a single-session update
 *  from clobbering the rest of the snapshot. */
def dump(stateFile: java.nio.file.Path, session: Option[String]): Unit = {
  if (!isTmuxRunning) { return }

  // Fetch session/window/layout/pane/command info per pane in one call
  val format  = "#{session_name}\t#{window_index}\t#{window_name}\t#{window_layout}\t#{pane_index}\t#{pane_current_path}\t#{pane_current_command}"
  val listCmd = session match {
    // Append ":" to the session name to disambiguate. Numeric session names like "0" or "1"
    // would be interpreted as window indexes by tmux without the trailing colon.
    case Some(s) => Seq("tmux", "list-panes", "-s", "-t", s + ":", "-F", format)
    case None    => Seq("tmux", "list-panes", "-a", "-F", format)
  }
  runCapture(listCmd) match {
    case None => return
    case Some(raw) => {
      val captured = buildWindows(raw.trim.linesIterator.toList)
      // With --session: merge into existing snapshot; without: replace all windows.
      val windows = session match {
        case Some(s) => readSnapshot(stateFile).map(_.windows).getOrElse(Nil).filterNot(_.session == s) ++ captured
        case None    => captured
      }
      val snapshot = Snapshot(version = 0, savedAt = java.time.Instant.now().toString, windows = windows)
      Option(stateFile.getParent).foreach(Files.createDirectories(_))
      Files.write(stateFile, snapshot.asJson.spaces2.getBytes(StandardCharsets.UTF_8))
      println(s"Saved ${captured.size} window(s), ${captured.map(_.panes.size).sum} pane(s) → $stateFile")
    }
  }
}

/** Converts a list of tab-separated lines into a list of WindowState, preserving window order. */
def buildWindows(lines: List[String]): List[WindowState] = {
  case class RawPane(
    session: String,
    windowIndex: Int,
    windowName: String,
    windowLayout: String,
    paneIndex: Int,
    currentPath: String,
    command: String
  )

  val rawPanes: List[RawPane] = lines.flatMap { line =>
    line.split("\t") match {
      case Array(session, widxStr, wname, layout, pidxStr, path, cmd) =>
        for {
          widx <- widxStr.toIntOption
          pidx <- pidxStr.toIntOption
        } yield RawPane(session, widx, wname, layout, pidx, path, cmd)
      case _ => None
    }
  }

  // Group by (session, windowIndex) using LinkedHashMap to preserve insertion order
  val grouped = scala.collection.mutable.LinkedHashMap.empty[(String, Int), List[RawPane]]
  rawPanes.foreach { rp =>
    val key = (rp.session, rp.windowIndex)
    grouped(key) = grouped.getOrElse(key, Nil) :+ rp
  }

  grouped.toList.map { case ((session, widx), panes) =>
    val sorted     = panes.sortBy(_.paneIndex)
    val windowName = sorted.head.windowName
    val layout     = sorted.head.windowLayout
    val paneStates = sorted.map { rp =>
      val (gitRoot, branch, isWorktree) = gitInfo(rp.currentPath)
      PaneState(rp.paneIndex, rp.currentPath, rp.command, gitRoot, branch, isWorktree)
    }
    WindowState(session, widx, windowName, layout, paneStates)
  }
}

/** Returns the git root, current branch, and whether path is in a worktree, or None values if not a git repo. */
def gitInfo(path: String): (Option[String], Option[String], Boolean) = {
  val root      = runCapture(Seq("git", "-C", path, "rev-parse", "--show-toplevel")).map(_.trim)
  val branch    = runCapture(Seq("git", "-C", path, "branch", "--show-current")).map(_.trim).filter(_.nonEmpty)
  val commonDir = runCapture(Seq("git", "-C", path, "rev-parse", "--git-common-dir")).map(_.trim)

  val isWorktree = (root, commonDir) match {
    case (Some(r), Some(cd)) => {
      // --git-common-dir may return a relative path (e.g. ".git"), so resolve it against path.
      // In the main worktree, the parent of commonDir equals show-toplevel;
      // in a linked worktree they differ.
      val resolvedCommon = Paths.get(path).resolve(cd).normalize()
      val mainRoot       = resolvedCommon.getParent
      try { Paths.get(r).toRealPath() != mainRoot.toRealPath() }
      catch { case _: Throwable => Paths.get(r).normalize() != mainRoot }
    }
    case _ => false
  }

  (root, branch, isWorktree)
}

def restore(stateFile: java.nio.file.Path, session: Option[String]): Unit = {
  if (!Files.exists(stateFile)) {
    System.err.println(s"Snapshot not found: $stateFile")
    sys.exit(1)
  }

  val json = new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8)
  decode[Snapshot](json) match {
    case Left(err) => {
      System.err.println(s"Parse error: $err")
      sys.exit(1)
    }
    case Right(snap) => {
      // When --session is given, restrict to that session and exit with an error if not found.
      val targetWindows = session match {
        case Some(s) => snap.windows.filter(_.session == s)
        case None    => snap.windows
      }
      session.foreach { s =>
        if (targetWindows.isEmpty) {
          System.err.println(s"Session '$s' not found in snapshot: $stateFile")
          sys.exit(1)
        }
      }
      println(s"Restoring from ${snap.savedAt}")
      targetWindows.groupBy(_.session).foreach { case (sessionName, windows) =>
        val exists = Process(Seq("tmux", "has-session", "-t", sessionName)).run(sink).exitValue() == 0
        val sorted = windows.sortBy(_.windowIndex)
        if (exists) {
          // Skip existing sessions to prevent duplicate windows when restore is run
          // inside a live tmux session.
          println(s"Session '$sessionName' already exists; skipping restore for it.")
        } else {
          createWindow(sessionName, sorted.head, isFirstWindow = true)
          sorted.tail.foreach(w => createWindow(sessionName, w, isFirstWindow = false))
        }
      }
      println("Done.")
    }
  }
}

/** Resolves the working directory for a pane. Returns currentPath if it exists,
 *  falls back to gitRoot (useful when a worktree is gone but the repo remains),
 *  then to home. Always returns a valid directory so window creation never fails
 *  due to a missing path, even for the first pane of a session. */
def resolveDir(pane: PaneState): String = {
  def isDir(p: String): Boolean = {
    try { Files.isDirectory(Paths.get(p)) }
    catch { case _: Throwable => false }
  }
  if (isDir(pane.currentPath)) { pane.currentPath }
  else { pane.gitRoot.filter(isDir).getOrElse(home) }
}

/** Restores one window. Additional panes are added with split-window and the saved layout
 *  is applied at the end. Panes that were running claude receive "claude -c" to resume
 *  the conversation.
 *
 *  Pane and window targets use tmux's immutable IDs (pane_id=%N, window_id=@N) rather than
 *  indexes (session:window.pane). Indexes are renumbered on session creation and diverge from
 *  saved values; immutable IDs are fixed at creation and always point to the right target. */
def createWindow(session: String, w: WindowState, isFirstWindow: Boolean): Unit = {
  val sortedPanes = w.panes.sortBy(_.paneIndex)
  val firstPane   = sortedPanes.head

  // Create the window and capture the first pane's pane_id (%N).
  // Both new-session and new-window support -P -F to print the new pane's ID.
  val firstDir = resolveDir(firstPane)
  val firstPaneId: Option[String] = if (isFirstWindow) {
    runCapture(Seq("tmux", "new-session", "-d", "-P", "-F", "#{pane_id}", "-s", session, "-n", w.windowName, "-c", firstDir))
      .map(_.trim).filter(_.nonEmpty)
  } else {
    // Append ":" to the session name to disambiguate numeric session names from window indexes.
    runCapture(Seq("tmux", "new-window", "-P", "-F", "#{pane_id}", "-t", session + ":", "-n", w.windowName, "-c", firstDir))
      .map(_.trim).filter(_.nonEmpty)
  }

  firstPaneId match {
    case None => {
      System.err.println(s"Failed to create window '${w.windowName}' in session '$session'; skipping.")
    }
    case Some(fpid) => {
      // Track (pane_id, saved pane) pairs
      val paneMapping = scala.collection.mutable.ListBuffer((fpid, firstPane))

      // Add subsequent panes with split-window, targeting the first pane ID to stay in the same window.
      // Record each new pane's pane_id.
      sortedPanes.tail.foreach { pane =>
        runCapture(Seq("tmux", "split-window", "-P", "-F", "#{pane_id}", "-t", fpid, "-c", resolveDir(pane)))
          .map(_.trim).filter(_.nonEmpty) match {
          case Some(pid) => paneMapping += ((pid, pane))
          case None      => System.err.println(s"Failed to split pane in window '${w.windowName}'; skipping that pane.")
        }
      }

      // Apply the saved layout only for multi-pane windows.
      // Layout target is window_id (@N), looked up from the first pane_id.
      if (sortedPanes.size > 1 && w.windowLayout.nonEmpty) {
        runCapture(Seq("tmux", "display-message", "-p", "-t", fpid, "#{window_id}"))
          .map(_.trim).filter(_.nonEmpty)
          .foreach(winId => Process(Seq("tmux", "select-layout", "-t", winId, w.windowLayout)).!)
      }

      // Send "claude -c" to panes that were running claude to resume the most recent
      // conversation in that directory (-c skips the picker).
      paneMapping.foreach { case (paneId, savedPane) =>
        if (savedPane.runningCommand == "claude") {
          Process(Seq("tmux", "send-keys", "-t", paneId, "claude -c", "Enter")).!
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// list subcommand
// ---------------------------------------------------------------------------

/** Git state of a pane's directory. mainRoot and worktreeName are only meaningful for a
 *  linked worktree. branch is None on a detached HEAD. */
case class WorktreeInfo(
  gitRoot: String,
  branch: Option[String],
  isWorktree: Boolean,
  worktreeName: Option[String],
  mainRoot: Option[String]
) derives Codec.AsObject

/** A live Claude Code interactive session, read from ~/.claude/sessions/<pid>.json. */
case class ClaudeSessionInfo(
  sessionId: String,
  name: Option[String],
  cwd: String,
  status: Option[String],
  version: Option[String],
  pid: Int,
  startedAt: Option[String],
  updatedAt: Option[String],
  firstUserMessage: Option[String]
) derives Codec.AsObject

/** A Claude Code agent occupying a pane, read from ~/.claude/teams/<team>/config.json.
 *  Agent panes are not registered in ~/.claude/sessions. */
case class ClaudeAgentInfo(
  agentName: String,
  agentType: Option[String],
  model: Option[String],
  teamName: String,
  leadSessionId: Option[String]
) derives Codec.AsObject

case class PaneInfo(
  paneId: String,
  paneIndex: Int,
  paneTty: String,
  panePid: Int,
  currentPath: String,
  runningCommand: String,
  worktree: Option[WorktreeInfo],
  claudeSession: Option[ClaudeSessionInfo],
  claudeAgent: Option[ClaudeAgentInfo]
) derives Codec.AsObject

case class WindowInfo(
  session: String,
  windowId: String,
  windowIndex: Int,
  windowName: String,
  windowLayout: String,
  isCurrent: Boolean,
  panes: List[PaneInfo]
) derives Codec.AsObject

case class Listing(
  generatedAt: String,
  currentSession: Option[String],
  currentPaneId: Option[String],
  windows: List[WindowInfo]
) derives Codec.AsObject

/** Raw tmux fields for one pane, in listFormat order.
 *
 *  This parser is deliberately separate from buildWindows. buildWindows matches a fixed
 *  7-element Array, so adding fields to dump's format string would send every pane to its
 *  `case _ => None` branch and make dump write an empty snapshot. dump runs unattended on a
 *  timer, so that failure would go unnoticed. */
case class ListRawPane(
  session: String,
  windowId: String,
  windowIndex: Int,
  windowName: String,
  windowLayout: String,
  paneId: String,
  paneIndex: Int,
  panePid: Int,
  paneTty: String,
  currentPath: String,
  command: String
)

val listFormat = List(
  "#{session_name}", "#{window_id}", "#{window_index}", "#{window_name}", "#{window_layout}",
  "#{pane_id}", "#{pane_index}", "#{pane_pid}", "#{pane_tty}", "#{pane_current_path}",
  "#{pane_current_command}"
).mkString("\t")

def parseListPanes(lines: List[String]): List[ListRawPane] = lines.flatMap { line =>
  // Limit -1 keeps trailing empty fields so the arity is always 11.
  line.split("\t", -1) match {
    case Array(sess, wid, widxStr, wname, layout, pid, pidxStr, ppidStr, tty, path, cmd) =>
      for {
        widx <- widxStr.toIntOption
        pidx <- pidxStr.toIntOption
        ppid <- ppidStr.toIntOption
      } yield ListRawPane(sess, wid, widx, wname, layout, pid, pidx, ppid, tty, path, cmd)
    case _ => None
  }
}

/** Resolves the session name and pane id of the pane this process is running in.
 *
 *  $TMUX is the only reliable "am I inside tmux" signal: `tmux display-message` exits 0
 *  outside tmux too and reports the last active session, so its exit code cannot be used.
 *  -t is mandatory — without it display-message answers for the client's active pane, which
 *  is a different pane than the one this process runs in whenever the two diverge. */
def currentSessionAndPane: (Option[String], Option[String]) = {
  if (sys.env.get("TMUX").isEmpty) { (None, None) }
  else {
    val paneId = sys.env.get("TMUX_PANE").map(_.trim).filter(_.nonEmpty)
    val session = paneId.flatMap { p =>
      runCapture(Seq("tmux", "display-message", "-p", "-t", p, "#{session_name}"))
        .map(_.trim).filter(_.nonEmpty)
    }
    (session, paneId)
  }
}

/** Session names the tmux server reports as attached. Returns an empty set when
 *  list-sessions fails, in which case the header simply omits the marker. */
def attachedSessions: Set[String] = {
  runCapture(Seq("tmux", "list-sessions", "-F", "#{session_name}\t#{session_attached}"))
    .map(_.trim.linesIterator.flatMap { l =>
      l.split("\t", -1) match {
        case Array(name, attached) if attached.trim.nonEmpty && attached.trim != "0" => Some(name)
        case _                                                                      => None
      }
    }.toSet)
    .getOrElse(Set.empty)
}

/** Per-run lookup tables for `list`. Built once so `git`, `ps` and the ~/.claude scans do
 *  not run per pane. Every field degrades to empty rather than failing the whole listing. */
case class ListContext(
  worktrees: Map[String, WorktreeInfo],
  sessionsByPanePid: Map[Int, ClaudeSessionInfo],
  agentsByPaneId: Map[String, ClaudeAgentInfo]
)

def collectListContext(panes: List[ListRawPane]): ListContext = {
  // Distinct paths only: panes frequently share a directory and each lookup forks git.
  val worktrees = panes.map(_.currentPath).distinct.flatMap(p => worktreeInfo(p).map(p -> _)).toMap
  val parents   = processParents
  val sessions  = collectClaudeSessions(panes.map(_.panePid).toSet, parents)
  ListContext(worktrees, sessions, Map.empty)
}

/** Reads and decodes a JSON file, degrading to None on any IO or parse failure. */
def readJson[A: io.circe.Decoder](p: java.nio.file.Path): Option[A] = {
  try { decode[A](new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).toOption }
  catch { case _: Throwable => None }
}

def isoFromMillis(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString

/** Lists the regular .json files in a directory, or Nil when it is absent or unreadable. */
def jsonFilesIn(dir: java.nio.file.Path): List[java.io.File] = {
  try {
    Option(dir.toFile.listFiles()).map(_.toList).getOrElse(Nil)
      .filter(f => f.isFile && f.getName.endsWith(".json"))
  } catch { case _: Throwable => Nil }
}

/** The fields of ~/.claude/sessions/<pid>.json that `list` reports. Circe ignores the rest,
 *  so extra fields upstream do not break decoding. */
case class RawClaudeSession(
  pid: Int,
  sessionId: String,
  cwd: String,
  name: Option[String],
  status: Option[String],
  version: Option[String],
  startedAt: Option[Long],
  updatedAt: Option[Long]
) derives Codec.AsObject

/** pid -> ppid for every process, from a single ps call.
 *
 *  /proc/<pid>/stat is deliberately not read directly: /proc reports a file size of 0 and
 *  Files.readAllBytes's behaviour on such files under Scala Native is unverified. */
def processParents: Map[Int, Int] = {
  runCapture(Seq("ps", "-eo", "pid=,ppid=")).map { out =>
    out.linesIterator.flatMap { l =>
      l.trim.split(" ").filter(_.nonEmpty) match {
        case Array(pid, ppid) =>
          for { p <- pid.toIntOption; q <- ppid.toIntOption } yield p -> q
        case _ => None
      }
    }.toMap
  }.getOrElse(Map.empty)
}

/** True when pid is alive and running claude. Session files outlive crashed processes, so
 *  without this check a recycled pid would attribute a dead session to an unrelated pane. */
def isClaudeProcess(pid: Int): Boolean = {
  runCapture(Seq("ps", "-o", "comm=", "-p", pid.toString)).map(_.trim).contains("claude")
}

/** Walks up the process tree from pid looking for one of targets.
 *
 *  A claude process is currently a direct child of the pane's shell, so depth 1 suffices
 *  today; the walk tolerates a wrapper process appearing in between later. */
def findAncestor(pid: Int, targets: Set[Int], parents: Map[Int, Int], maxDepth: Int = 10): Option[Int] = {
  var current            = pid
  var depth              = 0
  var found: Option[Int] = None
  var searching          = true
  while (searching && depth < maxDepth) {
    parents.get(current) match {
      case Some(parent) if targets.contains(parent) => { found = Some(parent); searching = false }
      // Stop at the top of the tree; pid 1 parents itself in some containers, which would loop.
      case Some(parent) if parent <= 1 || parent == current => searching = false
      case Some(parent)                                     => { current = parent; depth += 1 }
      case None                                             => searching = false
    }
  }
  found
}

/** Live Claude Code sessions, keyed by the pane_pid of the pane that hosts them. */
def collectClaudeSessions(panePids: Set[Int], parents: Map[Int, Int]): Map[Int, ClaudeSessionInfo] = {
  jsonFilesIn(Paths.get(home, ".claude", "sessions")).flatMap { f =>
    for {
      raw <- readJson[RawClaudeSession](f.toPath)
      if isClaudeProcess(raw.pid)
      pane <- findAncestor(raw.pid, panePids, parents)
    } yield pane -> ClaudeSessionInfo(
      sessionId = raw.sessionId,
      name = raw.name,
      cwd = raw.cwd,
      status = raw.status,
      version = raw.version,
      pid = raw.pid,
      startedAt = raw.startedAt.map(isoFromMillis),
      updatedAt = raw.updatedAt.map(isoFromMillis),
      firstUserMessage = None
    )
  }.toMap
}

/** Git state of a directory, resolved in one rev-parse call that prints the four requested
 *  values as four lines. Returns None outside a git repository, where rev-parse exits 128.
 *
 *  gitInfo is deliberately left alone: its three return values map 1:1 onto PaneState's
 *  fields and dump depends on that shape. */
def worktreeInfo(path: String): Option[WorktreeInfo] = {
  val out = runCapture(Seq(
    "git", "-C", path, "rev-parse",
    "--show-toplevel", "--git-common-dir", "--git-dir", "--abbrev-ref", "HEAD"
  ))
  out.flatMap(_.trim.linesIterator.map(_.trim).toList match {
    case root :: commonDir :: gitDir :: headRef :: Nil => {
      // Both directories come back relative (".git") when git is run from the repository root,
      // so resolve them against path before comparing.
      val base           = Paths.get(path)
      val resolvedCommon = base.resolve(commonDir).normalize()
      val resolvedGitDir = base.resolve(gitDir).normalize()
      // A linked worktree's git dir is .git/worktrees/<name>, which differs from the common
      // dir; in the main worktree the two are the same path.
      val isWorktree = resolvedCommon != resolvedGitDir
      Some(WorktreeInfo(
        gitRoot = root,
        // rev-parse prints the literal "HEAD" on a detached HEAD, which is not a branch name.
        branch = Some(headRef).filter(b => b.nonEmpty && b != "HEAD"),
        isWorktree = isWorktree,
        worktreeName = if (isWorktree) { Option(resolvedGitDir.getFileName).map(_.toString) } else { None },
        mainRoot = if (isWorktree) { Option(resolvedCommon.getParent).map(_.toString) } else { None }
      ))
    }
    case _ => None
  })
}

/** Groups panes into windows, preserving the order tmux reported them in. */
def buildListing(panes: List[ListRawPane], currentPaneId: Option[String], ctx: ListContext): List[WindowInfo] = {
  // window_id is immutable and unique across the server, but pair it with the session name
  // so the grouping key stays meaningful in the output.
  val grouped = scala.collection.mutable.LinkedHashMap.empty[(String, String), List[ListRawPane]]
  panes.foreach { p =>
    val key = (p.session, p.windowId)
    grouped(key) = grouped.getOrElse(key, Nil) :+ p
  }

  grouped.toList.map { case ((session, windowId), ps) =>
    val sorted = ps.sortBy(_.paneIndex)
    val head   = sorted.head
    val infos = sorted.map { p =>
      PaneInfo(
        paneId = p.paneId,
        paneIndex = p.paneIndex,
        paneTty = p.paneTty,
        panePid = p.panePid,
        currentPath = p.currentPath,
        runningCommand = p.command,
        worktree = ctx.worktrees.get(p.currentPath),
        claudeSession = ctx.sessionsByPanePid.get(p.panePid),
        claudeAgent = ctx.agentsByPaneId.get(p.paneId)
      )
    }
    WindowInfo(
      session = session,
      windowId = windowId,
      windowIndex = head.windowIndex,
      windowName = head.windowName,
      windowLayout = head.windowLayout,
      isCurrent = currentPaneId.exists(id => sorted.exists(_.paneId == id)),
      panes = infos
    )
  }
}

def list(session: Option[String], all: Boolean, asJson: Boolean): Unit = {
  // Unlike dump, which stays silent to protect the snapshot, list has nothing to protect
  // and reports the failure instead.
  if (!isTmuxRunning) {
    System.err.println("tmux-snapshot: no tmux server is running")
    sys.exit(1)
  }

  val (currentSession, currentPaneId) = currentSessionAndPane
  val target: Option[String] =
    if (all) { None }
    else {
      session.orElse(currentSession) match {
        case Some(s) => Some(s)
        case None => {
          System.err.println("tmux-snapshot: not running inside tmux; pass --session <name> or --all")
          sys.exit(1)
        }
      }
    }

  val listCmd = target match {
    // Append ":" so a numeric session name is not read as a window index.
    case Some(s) => Seq("tmux", "list-panes", "-s", "-t", s + ":", "-F", listFormat)
    case None    => Seq("tmux", "list-panes", "-a", "-F", listFormat)
  }

  val raw = runCapture(listCmd) match {
    case Some(r) => r
    case None => {
      System.err.println(target.fold("tmux-snapshot: failed to list panes")(s => s"tmux-snapshot: session '$s' not found"))
      sys.exit(1)
    }
  }

  val panes   = parseListPanes(raw.trim.linesIterator.toList)
  val ctx     = collectListContext(panes)
  val windows = buildListing(panes, currentPaneId, ctx)
  val listing = Listing(java.time.Instant.now().toString, currentSession, currentPaneId, windows)

  if (asJson) { println(listing.asJson.spaces2) }
  else { printListing(listing, attachedSessions) }
}

// --- human readable rendering ---

/** Replaces a leading $HOME with "~". */
def abbreviateHome(path: String): String = {
  if (path == home) { "~" }
  else if (path.startsWith(home + "/")) { "~" + path.drop(home.length) }
  else { path }
}

/** Shortens a path to at most `max` code points by dropping leading path components and
 *  prefixing "…". The tail is kept rather than the head because a worktree or repository
 *  name sits at the end and is what identifies the pane, while the head is nearly always
 *  "~/src/github.com/<owner>/" and carries little information. */
def shortenPath(path: String, max: Int = 52): String = {
  if (path.codePointCount(0, path.length) <= max) { path }
  else {
    // Suffixes are generated longest-first, so the first one that fits is the longest one.
    val fitting = path.indices.iterator
      .filter(i => path.charAt(i) == '/')
      .map(i => path.substring(i))
      .find(s => s.codePointCount(0, s.length) + 1 <= max)
    fitting match {
      case Some(s) => "…" + s
      case None => {
        // A single component is longer than the budget, so no "/" boundary suffix fits.
        "…" + path.substring(path.offsetByCodePoints(path.length, -(max - 1)))
      }
    }
  }
}

/** Truncates to `max` code points, appending "…" only when something was actually dropped. */
def truncateText(s: String, max: Int): String = {
  if (s.codePointCount(0, s.length) <= max) { s }
  else { s.substring(0, s.offsetByCodePoints(0, max)) + "…" }
}

/** Renders a duration as "2h12m", or "12m" when under an hour. */
def humanDuration(millis: Long): String = {
  val minutes = millis / 60000L
  val h       = minutes / 60
  val m       = minutes % 60
  if (h > 0) { s"${h}h${m}m" } else { s"${m}m" }
}

/** Uptime from an ISO8601 instant to now. None when the string cannot be parsed. */
def uptimeSince(iso: String): Option[String] = {
  try {
    val started = java.time.Instant.parse(iso).toEpochMilli
    val now     = java.time.Instant.now().toEpochMilli
    if (now >= started) { Some(humanDuration(now - started)) } else { None }
  } catch { case _: Throwable => None }
}

/** Pads to `width` columns, counting code points. Returns the string unchanged when it is
 *  already at least that wide, so a long field pushes the next column right instead of
 *  being cut. */
def padTo(s: String, width: Int): String = {
  val n = s.codePointCount(0, s.length)
  if (n >= width) { s } else { s + " " * (width - n) }
}

def printListing(listing: Listing, attached: Set[String]): Unit = {
  val bySession = scala.collection.mutable.LinkedHashMap.empty[String, List[WindowInfo]]
  listing.windows.foreach(w => bySession(w.session) = bySession.getOrElse(w.session, Nil) :+ w)

  bySession.toList.zipWithIndex.foreach { case ((name, windows), i) =>
    if (i > 0) { println() }
    val paneCount = windows.map(_.panes.size).sum
    val marker    = if (attached.contains(name)) { ", attached" } else { "" }
    println(s"session $name  (${windows.size} windows, $paneCount panes$marker)")
    windows.foreach { w => println(); printWindow(w) }
  }
}

def printWindow(w: WindowInfo): Unit = {
  val head = f" ${w.windowIndex}%2d: ${w.windowName}%s"
  println(if (w.isCurrent) { padTo(head, 69) + "← current" } else { head })

  w.panes.foreach { p =>
    val path = shortenPath(abbreviateHome(p.currentPath))
    println(s"    ${p.paneIndex}  ${padTo(path, 52)}  ${p.runningCommand}")

    // Omitted entirely for panes outside a git repository, and each field is omitted when
    // it could not be resolved.
    p.worktree.foreach { wt =>
      val tag =
        if (wt.isWorktree) {
          (wt.worktreeName, wt.mainRoot.map(r => Paths.get(r).getFileName.toString)) match {
            case (Some(n), Some(main)) => Some(s"[worktree: $n → $main]")
            case (Some(n), None)       => Some(s"[worktree: $n]")
            case _                     => None
          }
        } else { None }
      val fields = List(wt.branch, tag).flatten
      if (fields.nonEmpty) { println("       git  " + fields.mkString("  ")) }
    }

    p.claudeSession.foreach { cs =>
      val fields = List(
        cs.name,
        cs.status,
        Some(cs.sessionId.take(8)),
        cs.startedAt.flatMap(uptimeSince),
        cs.firstUserMessage.map(m => "\"" + m + "\"")
      ).flatten
      println("       cc   " + fields.mkString("  "))
    }

    p.claudeAgent.foreach { ca =>
      val meta = List(ca.agentType, ca.model).flatten.mkString("/")
      val head = if (meta.nonEmpty) { s"agent ${ca.agentName} ($meta)" } else { s"agent ${ca.agentName}" }
      println("       cc   " + List(head, s"team ${ca.teamName}").mkString("  "))
    }
  }
}
