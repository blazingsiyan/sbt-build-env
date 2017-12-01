organization in ThisBuild := "com.doubajam"

version in ThisBuild := "0.0.1-SNAPSHOT"

scalaVersion in ThisBuild := "2.10.6"

lazy val root = (project in file("."))
  .settings(
    name := "sbt-build-env",
    description := "sbt plugin for different build environment",
    sbtPlugin := true,
    publishArtifact in Test := false
  )
