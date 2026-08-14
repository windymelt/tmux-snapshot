import scala.scalanative.build.*

enablePlugins(ScalaNativePlugin, BuildInfoPlugin)

name         := "tmux-snapshot"
organization := "windymelt"
// Release builds set TMUX_SNAPSHOT_VERSION from the git tag (see release.yml).
// Local and CI builds report a placeholder so `--version` never claims a
// release that was not tagged. An empty value is treated the same as unset,
// because a workflow that always exports the variable would otherwise inject
// an empty version string.
version := sys.env
  .get("TMUX_SNAPSHOT_VERSION")
  .filter(_.nonEmpty)
  .getOrElse("0.0.0-SNAPSHOT")
scalaVersion := "3.3.8"

Compile / mainClass := Some("Main")

scalacOptions += "-no-indent"

libraryDependencies ++= Seq(
  "io.circe"           %% "circe-core"      % "0.14.15",
  "io.circe"           %% "circe-generic"   % "0.14.15",
  "io.circe"           %% "circe-parser"    % "0.14.15",
  "io.github.cquiroz" %% "scala-java-time"  % "2.6.0",
  "com.github.scopt"   %% "scopt"           % "4.1.0",
  "tech.neander"       %% "cue4s"           % "0.0.9"
)

val nativeCfg: NativeConfig => NativeConfig =
  _.withMode(Mode.releaseFast)
   .withLTO(LTO.thin)

nativeConfig ~= nativeCfg

Test / nativeConfig ~= nativeCfg
