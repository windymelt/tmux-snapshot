//> using platform scala-native
//> using scala 3.3.3
//> using dep io.circe::circe-core::0.14.15
//> using dep io.circe::circe-generic::0.14.15
//> using dep io.circe::circe-parser::0.14.15
//> using dep io.github.cquiroz::scala-java-time::2.6.0
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

/** ペイン単位の状態。runningCommand はdump時点でフォアグラウンドで動いていたコマンド名 */
case class PaneState(
  paneIndex: Int,
  currentPath: String,
  runningCommand: String,
  gitRoot: Option[String],
  branch: Option[String],
  isWorktree: Boolean
) derives Codec.AsObject

/** ウィンドウ単位の状態。windowLayout はtmuxのレイアウト文字列 */
case class WindowState(
  session: String,
  windowIndex: Int,
  windowName: String,
  windowLayout: String,
  panes: List[PaneState]
) derives Codec.AsObject

/** 保存時刻と全ウィンドウ状態のスナップショット */
case class Snapshot(
  version: Int,
  savedAt: String,
  windows: List[WindowState]
) derives Codec.AsObject

val home      = sys.env.getOrElse("HOME", System.getProperty("user.home"))
val stateDir  = Paths.get(home, ".local", "share", "tmux-snapshot")
val stateFile = stateDir.resolve("state.json")

/** stdoutもstderrも捨てるロガー。tmuxやgitのエラー出力を抑制するために使用する */
val sink = ProcessLogger(_ => (), _ => ())

object Main {
  def main(args: Array[String]): Unit = {
    args.headOption match {
      case Some("dump")    => dump()
      case Some("restore") => restore()
      case _ => {
        System.err.println("Usage: tmux-snapshot [dump|restore]")
        sys.exit(1)
      }
    }
  }
}

def isTmuxRunning: Boolean = {
  Process(Seq("tmux", "list-sessions")).run(sink).exitValue() == 0
}

/** コマンドを実行してstdoutを返す。終了コードが非ゼロの場合はNoneを返す */
def runCapture(cmd: Seq[String]): Option[String] = {
  try { Some(Process(cmd).!!(sink)) }
  catch { case _: Throwable => None }
}

def dump(): Unit = {
  if (!isTmuxRunning) { return }

  // ペイン単位で session/window/layout/pane/command 情報をまとめて取得する
  val format = "#{session_name}\t#{window_index}\t#{window_name}\t#{window_layout}\t#{pane_index}\t#{pane_current_path}\t#{pane_current_command}"
  runCapture(Seq("tmux", "list-panes", "-a", "-F", format)) match {
    case None => return
    case Some(raw) => {
      val windows  = buildWindows(raw.trim.linesIterator.toList)
      val snapshot = Snapshot(version = 0, savedAt = java.time.Instant.now().toString, windows = windows)
      Files.createDirectories(stateDir)
      Files.write(stateFile, snapshot.asJson.spaces2.getBytes(StandardCharsets.UTF_8))
      println(s"Saved ${windows.size} window(s), ${windows.map(_.panes.size).sum} pane(s) → $stateFile")
    }
  }
}

/** タブ区切り行のリストをWindowStateのリストに変換する。ウィンドウ出現順を保持する */
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

  // LinkedHashMap で (session, windowIndex) をキーに出現順を保持しながらグループ化する
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

/** pathがgitリポジトリ内であれば、リポジトリルート・ブランチ・worktreeかどうかを返す */
def gitInfo(path: String): (Option[String], Option[String], Boolean) = {
  val root      = runCapture(Seq("git", "-C", path, "rev-parse", "--show-toplevel")).map(_.trim)
  val branch    = runCapture(Seq("git", "-C", path, "branch", "--show-current")).map(_.trim).filter(_.nonEmpty)
  val commonDir = runCapture(Seq("git", "-C", path, "rev-parse", "--git-common-dir")).map(_.trim)

  val isWorktree = (root, commonDir) match {
    case (Some(r), Some(cd)) => {
      // --git-common-dir は相対パス（".git" など）を返す場合があるため path 基準で解決する。
      // メインworktreeでは commonDir の親 == show-toplevel が成立し、
      // 追加worktreeでは両者が異なる。
      val resolvedCommon = Paths.get(path).resolve(cd).normalize()
      val mainRoot       = resolvedCommon.getParent
      try { Paths.get(r).toRealPath() != mainRoot.toRealPath() }
      catch { case _: Throwable => Paths.get(r).normalize() != mainRoot }
    }
    case _ => false
  }

  (root, branch, isWorktree)
}

def restore(): Unit = {
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
      println(s"Restoring from ${snap.savedAt}")
      snap.windows.groupBy(_.session).foreach { case (sessionName, windows) =>
        val exists = Process(Seq("tmux", "has-session", "-t", sessionName)).run(sink).exitValue() == 0
        val sorted = windows.sortBy(_.windowIndex)
        if (exists) {
          // 既存セッションには追加しない。誤って起動中のtmux上でrestoreした場合に
          // ウィンドウが重複生成されるのを防ぐため、復元は「セッションが存在しない場合のみ」行う。
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

/** 1ウィンドウ分を復元する。複数ペインがあればsplit-windowで追加し、最後にレイアウトを適用する。
 *  claude が動いていたペインには claude -c を送り込んでセッションを再開する。 */
def createWindow(session: String, w: WindowState, isFirstWindow: Boolean): Unit = {
  val sortedPanes = w.panes.sortBy(_.paneIndex)
  val firstPane   = sortedPanes.head

  // ウィンドウを作成し、実際に割り当てられたインデックスを取得する。
  // ウィンドウ名が重複する場合でも正確にターゲットできるようインデックスで扱う。
  val actualWindowIndex: Int = if (isFirstWindow) {
    Process(Seq("tmux", "new-session", "-d", "-s", session, "-n", w.windowName, "-c", firstPane.currentPath)).!
    runCapture(Seq("tmux", "display-message", "-p", "-t", session, "#{window_index}"))
      .flatMap(_.trim.toIntOption)
      .getOrElse(0)
  } else {
    runCapture(Seq("tmux", "new-window", "-P", "-F", "#{window_index}", "-t", session, "-n", w.windowName, "-c", firstPane.currentPath))
      .flatMap(_.trim.toIntOption)
      .getOrElse(w.windowIndex)
  }

  val target = s"$session:$actualWindowIndex"

  // 最初のペインの実際のインデックスを取得し、(実インデックス, 保存済みペイン) のペアを追跡する
  val firstActualPaneIndex = runCapture(Seq("tmux", "display-message", "-p", "-t", target, "#{pane_index}"))
    .flatMap(_.trim.toIntOption)
    .getOrElse(0)

  val paneMapping = scala.collection.mutable.ListBuffer((firstActualPaneIndex, firstPane))

  // 2ペイン目以降を split-window で追加し、割り当てられたインデックスを記録する
  sortedPanes.tail.foreach { pane =>
    val newPaneIndex = runCapture(Seq("tmux", "split-window", "-P", "-F", "#{pane_index}", "-t", target, "-c", pane.currentPath))
      .flatMap(_.trim.toIntOption)
      .getOrElse(-1)
    paneMapping += ((newPaneIndex, pane))
  }

  // 複数ペインがある場合は保存済みレイアウトを適用する
  if (sortedPanes.size > 1) {
    Process(Seq("tmux", "select-layout", "-t", target, w.windowLayout)).!
  }

  // claude が動いていたペインには claude -c を送り込み、
  // そのディレクトリの直近の会話を再開する（-c はピッカーを開かず自動再開する）
  paneMapping.foreach { case (actualPaneIndex, savedPane) =>
    if (savedPane.runningCommand == "claude") {
      Process(Seq("tmux", "send-keys", "-t", s"$target.$actualPaneIndex", "claude -c", "Enter")).!
    }
  }
}
