//> using platform scala-native
//> using scala 3.3.8
//> using nativeVersion "0.5.12"
//> using options -Yfuture-lazy-vals -java-output-version:11
//> using dep io.circe::circe-core::0.14.15
//> using dep io.circe::circe-generic::0.14.15
//> using dep io.circe::circe-parser::0.14.15
//> using dep io.github.cquiroz::scala-java-time::2.6.0
//> using dep com.github.scopt::scopt::4.1.0
//> using nativeMode release-fast
//> using nativeLto thin
//> using mainClass Main
//> using scalacOptions -no-indent

import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import scala.sys.process.*
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import scopt.OParser

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

/** Parsed command-line config. When session is Some, dump/restore targets only that session. */
case class Config(
  command: String = "",
  stateFile: java.nio.file.Path = defaultStateFile,
  session: Option[String] = None
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
    arg[String]("<command>")
      .action((x, c) => c.copy(command = x))
      .validate(x =>
        if (x == "dump" || x == "restore") success
        else failure(s"command must be dump or restore: $x")
      )
      .text("command to run (dump or restore)")
  )
}

object Main {
  def main(args: Array[String]): Unit = {
    OParser.parse(cliParser, args, Config()) match {
      case Some(cfg) =>
        cfg.command match {
          case "dump"    => dump(cfg.stateFile, cfg.session)
          case "restore" => restore(cfg.stateFile, cfg.session)
        }
      case None => sys.exit(1)
    }
  }
}

def isTmuxRunning: Boolean = {
  Process(Seq("tmux", "list-sessions")).run(sink).exitValue() == 0
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
