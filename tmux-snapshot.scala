//> using platform scala-native
//> using scala 3.3.3
//> using dep io.circe::circe-core::0.14.15
//> using dep io.circe::circe-generic::0.14.15
//> using dep io.circe::circe-parser::0.14.15
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

/** tmuxの各ウィンドウ（アクティブペインのパスを含む）とgit情報を表す */
case class WindowState(
  session: String,
  windowIndex: Int,
  windowName: String,
  currentPath: String,
  gitRoot: Option[String],
  branch: Option[String],
  isWorktree: Boolean
) derives Codec.AsObject

/** 保存時刻と全ウィンドウ状態のスナップショット */
case class Snapshot(
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

/** tmuxサーバーが起動中かどうかを確認する */
def isTmuxRunning: Boolean = {
  Process(Seq("tmux", "list-sessions")).run(sink).exitValue() == 0
}

/** コマンドを実行してstdoutを返す。終了コードが非ゼロの場合はNoneを返す */
def runCapture(cmd: Seq[String]): Option[String] = {
  try { Some(Process(cmd).!!(sink)) }
  catch { case _: Throwable => None }
}

def dump(): Unit = {
  // tmuxが起動していない場合は何もしない
  if (!isTmuxRunning) { return }

  // session_name, window_index, window_name, アクティブペインのカレントディレクトリを取得
  val format = "#{session_name}\t#{window_index}\t#{window_name}\t#{pane_current_path}"
  runCapture(Seq("tmux", "list-windows", "-a", "-F", format)) match {
    case None => return
    case Some(raw) => {
      val windows  = raw.trim.linesIterator.toList.flatMap(parseLine)
      val snapshot = Snapshot(java.time.Instant.now().toString, windows)
      Files.createDirectories(stateDir)
      Files.write(stateFile, snapshot.asJson.spaces2.getBytes(StandardCharsets.UTF_8))
      println(s"Saved ${windows.size} window(s) → $stateFile")
    }
  }
}

/** タブ区切りの1行をWindowStateにパースする */
def parseLine(line: String): Option[WindowState] = {
  line.split("\t") match {
    case Array(session, idxStr, name, path) => {
      val idx                         = idxStr.toIntOption.getOrElse(0)
      val (gitRoot, branch, isWorktree) = gitInfo(path)
      Some(WindowState(session, idx, name, path, gitRoot, branch, isWorktree))
    }
    case _ => None
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
        if (!exists) {
          // セッションごと新規作成し、先頭ウィンドウをそのまま初期ウィンドウとして使う
          val h = sorted.head
          Process(Seq("tmux", "new-session", "-d", "-s", sessionName, "-n", h.windowName, "-c", h.currentPath)).!
          sorted.tail.foreach { w =>
            Process(Seq("tmux", "new-window", "-t", sessionName, "-n", w.windowName, "-c", w.currentPath)).!
          }
        } else {
          // 既存セッションにウィンドウを追加する
          sorted.foreach { w =>
            Process(Seq("tmux", "new-window", "-t", sessionName, "-n", w.windowName, "-c", w.currentPath)).!
          }
        }
      }
      println("Done.")
    }
  }
}
