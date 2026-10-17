ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "dev.evanwilson"

lazy val root = (project in file("."))
  .settings(
    name := "matinee",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-mllib" % "4.0.4",
      "org.apache.spark" %% "spark-sql"   % "4.0.4",
      "org.scalatest"    %% "scalatest"   % "3.2.19" % Test,
    ),
    // Spark needs these opens on Java 17+.
    run / fork := true,
    Test / fork := true,
    javaOptions ++= Seq(
      "-Xmx4g",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
      "-Dlog4j2.level=WARN",
      "-Dstdout.encoding=UTF-8",
      "-Dfile.encoding=UTF-8",
    ),
    run / outputStrategy := Some(StdoutOutput),
  )
