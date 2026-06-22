import scala.scalanative.build.*

enablePlugins(ScalaNativePlugin)

name         := "tmux-snapshot"
organization := "windymelt"
version      := "0.1.0-SNAPSHOT"
scalaVersion := "3.3.8"

Compile / mainClass := Some("Main")

scalacOptions ++= Seq(
  "-Yfuture-lazy-vals",
  "-java-output-version:11",
  "-no-indent"
)

libraryDependencies ++= Seq(
  "io.circe"           %%% "circe-core"      % "0.14.15",
  "io.circe"           %%% "circe-generic"   % "0.14.15",
  "io.circe"           %%% "circe-parser"    % "0.14.15",
  "io.github.cquiroz" %%% "scala-java-time"  % "2.6.0",
  "com.github.scopt"   %%% "scopt"           % "4.1.0"
)

nativeConfig ~= {
  _.withMode(Mode.releaseFast)
   .withLTO(LTO.thin)
}
