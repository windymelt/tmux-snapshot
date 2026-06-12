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

val home             = sys.env.getOrElse("HOME", System.getProperty("user.home"))
// --state 未指定時のデフォルト保存先
val defaultStateFile = Paths.get(home, ".local", "share", "tmux-snapshot", "state.json")

/** stdoutもstderrも捨てるロガー。tmuxやgitのエラー出力を抑制するために使用する */
val sink = ProcessLogger(_ => (), _ => ())

val usage = "Usage: tmux-snapshot [--state <path>] [--session <name>] [dump|restore]"

/** 解析済みのコマンドライン設定。session が Some の場合、dump/restore はそのセッションのみを対象にする */
case class Config(
  command: String,
  stateFile: java.nio.file.Path,
  session: Option[String]
)

object Main {
  def main(args: Array[String]): Unit = {
    parseArgs(args.toList) match {
      case Left(msg) => {
        System.err.println(msg)
        sys.exit(1)
      }
      case Right(cfg) => {
        cfg.command match {
          case "dump"    => dump(cfg.stateFile, cfg.session)
          case "restore" => restore(cfg.stateFile, cfg.session)
          case _ => {
            System.err.println(usage)
            sys.exit(1)
          }
        }
      }
    }
  }
}

/** 引数を解析して Config を返す。`--state <path>` `--session <name>` をそれぞれ
 *  スペース区切り・`=`区切りの両形式で受け付ける。未指定時は state=defaultStateFile, session=None。 */
def parseArgs(args: List[String]): Either[String, Config] = {
  def loop(rest: List[String], cmd: Option[String], state: Option[String], session: Option[String]): Either[String, Config] = {
    rest match {
      case Nil => {
        cmd match {
          case Some(c) => Right(Config(c, state.map(Paths.get(_)).getOrElse(defaultStateFile), session))
          case None    => Left(usage)
        }
      }
      case "--state" :: value :: tail => loop(tail, cmd, Some(value), session)
      case "--state" :: Nil           => Left("--state requires a path argument")
      case arg :: tail if arg.startsWith("--state=")   => loop(tail, cmd, Some(arg.drop("--state=".length)), session)
      case "--session" :: value :: tail => loop(tail, cmd, state, Some(value))
      case "--session" :: Nil           => Left("--session requires a name argument")
      case arg :: tail if arg.startsWith("--session=") => loop(tail, cmd, state, Some(arg.drop("--session=".length)))
      case arg :: _ if arg.startsWith("-")             => Left(s"Unknown option: $arg")
      case arg :: tail => {
        if (cmd.isDefined) { Left(s"Unexpected argument: $arg") }
        else { loop(tail, Some(arg), state, session) }
      }
    }
  }
  loop(args, None, None, None)
}

def isTmuxRunning: Boolean = {
  Process(Seq("tmux", "list-sessions")).run(sink).exitValue() == 0
}

/** コマンドを実行してstdoutを返す。終了コードが非ゼロの場合はNoneを返す */
def runCapture(cmd: Seq[String]): Option[String] = {
  try { Some(Process(cmd).!!(sink)) }
  catch { case _: Throwable => None }
}

/** スナップショットを読み込む。ファイルが無い、または壊れている場合は None を返す */
def readSnapshot(stateFile: java.nio.file.Path): Option[Snapshot] = {
  if (!Files.exists(stateFile)) { None }
  else {
    val json = new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8)
    decode[Snapshot](json).toOption
  }
}

/** tmux の状態を取得して stateFile に保存する。
 *  session が Some の場合はそのセッションのみを対象とし、既存スナップショット中の
 *  他セッションは保持したまま当該セッションのみを差し替える（マージ）。これにより
 *  特定セッションだけを更新しても他の復元材料を壊さない。 */
def dump(stateFile: java.nio.file.Path, session: Option[String]): Unit = {
  if (!isTmuxRunning) { return }

  // ペイン単位で session/window/layout/pane/command 情報をまとめて取得する
  val format  = "#{session_name}\t#{window_index}\t#{window_name}\t#{window_layout}\t#{pane_index}\t#{pane_current_path}\t#{pane_current_command}"
  val listCmd = session match {
    // 末尾コロン "name:" でセッションを明示する。数値セッション名（"0","1"等）は
    // "-t 1" だとウィンドウインデックス1と解釈され誤ターゲットになるため。
    case Some(s) => Seq("tmux", "list-panes", "-s", "-t", s + ":", "-F", format)
    case None    => Seq("tmux", "list-panes", "-a", "-F", format)
  }
  runCapture(listCmd) match {
    case None => return
    case Some(raw) => {
      val captured = buildWindows(raw.trim.linesIterator.toList)
      // --session 指定時は他セッションを保持してマージする。未指定時は全置換。
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
      // --session 指定時は当該セッションのみに絞り込む。該当が無ければエラーで終了する。
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

/** -c に渡す作業ディレクトリを解決する。currentPath が存在すればそれを、
 *  無ければ gitRoot（worktreeが消えてもリポジトリ本体が残っている場合）、
 *  それも無ければ home を返す。ディレクトリが消えていてもウィンドウ作成自体は
 *  必ず成功させ、特に最初のペインのパス消失でセッション全体の復元が巻き込まれるのを防ぐ。 */
def resolveDir(pane: PaneState): String = {
  def isDir(p: String): Boolean = {
    try { Files.isDirectory(Paths.get(p)) }
    catch { case _: Throwable => false }
  }
  if (isDir(pane.currentPath)) { pane.currentPath }
  else { pane.gitRoot.filter(isDir).getOrElse(home) }
}

/** 1ウィンドウ分を復元する。複数ペインがあればsplit-windowで追加し、最後にレイアウトを適用する。
 *  claude が動いていたペインには claude -c を送り込んでセッションを再開する。
 *
 *  ターゲット指定にはインデックス（session:window.pane）ではなく、tmuxの不変ID
 *  （pane_id=%N, window_id=@N）を使う。インデックスは新規セッションで詰め直されるため
 *  保存値とずれるが、不変IDは作成時に確定し以降変わらないため確実にターゲットできる。 */
def createWindow(session: String, w: WindowState, isFirstWindow: Boolean): Unit = {
  val sortedPanes = w.panes.sortBy(_.paneIndex)
  val firstPane   = sortedPanes.head

  // ウィンドウを作成し、その最初のペインの pane_id (%N) を取得する。
  // new-session / new-window はいずれも -P -F で新規ペインのIDを出力できる。
  val firstDir = resolveDir(firstPane)
  val firstPaneId: Option[String] = if (isFirstWindow) {
    runCapture(Seq("tmux", "new-session", "-d", "-P", "-F", "#{pane_id}", "-s", session, "-n", w.windowName, "-c", firstDir))
      .map(_.trim).filter(_.nonEmpty)
  } else {
    // 末尾コロン "session:" でセッションを明示する。数値セッション名では "-t 1" が
    // ウィンドウインデックス1と解釈され、別セッションに誤ってウィンドウを作ってしまうため。
    runCapture(Seq("tmux", "new-window", "-P", "-F", "#{pane_id}", "-t", session + ":", "-n", w.windowName, "-c", firstDir))
      .map(_.trim).filter(_.nonEmpty)
  }

  firstPaneId match {
    case None => {
      System.err.println(s"Failed to create window '${w.windowName}' in session '$session'; skipping.")
    }
    case Some(fpid) => {
      // (pane_id, 保存済みペイン) のペアを追跡する
      val paneMapping = scala.collection.mutable.ListBuffer((fpid, firstPane))

      // 2ペイン目以降を split-window で追加する。-t に最初のペインのIDを指定すると
      // 同じウィンドウ内に分割される。新ペインの pane_id を記録する。
      sortedPanes.tail.foreach { pane =>
        runCapture(Seq("tmux", "split-window", "-P", "-F", "#{pane_id}", "-t", fpid, "-c", resolveDir(pane)))
          .map(_.trim).filter(_.nonEmpty) match {
          case Some(pid) => paneMapping += ((pid, pane))
          case None      => System.err.println(s"Failed to split pane in window '${w.windowName}'; skipping that pane.")
        }
      }

      // 複数ペインかつ保存済みレイアウトがある場合のみ適用する。
      // レイアウトは window_id (@N) を対象にする（pane_id から引く）。
      if (sortedPanes.size > 1 && w.windowLayout.nonEmpty) {
        runCapture(Seq("tmux", "display-message", "-p", "-t", fpid, "#{window_id}"))
          .map(_.trim).filter(_.nonEmpty)
          .foreach(winId => Process(Seq("tmux", "select-layout", "-t", winId, w.windowLayout)).!)
      }

      // claude が動いていたペインには claude -c を送り込み、
      // そのディレクトリの直近の会話を再開する（-c はピッカーを開かず自動再開する）
      paneMapping.foreach { case (paneId, savedPane) =>
        if (savedPane.runningCommand == "claude") {
          Process(Seq("tmux", "send-keys", "-t", paneId, "claude -c", "Enter")).!
        }
      }
    }
  }
}
