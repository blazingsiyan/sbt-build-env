package sbtbuildenv

import java.util.Properties

import sbt.Defaults._
import sbt.Keys._
import sbt.{Def, _}
import sbtbuildenv.EnvPlugin.autoImport._

import scala.collection.JavaConverters._

trait FilterKeys {
  lazy val filterDirectoryName = settingKey[String]("Default filter directory name.")
  lazy val filterDirectory = settingKey[File]("Default filter directory, used for filters.")
  lazy val envFilterDirectory = settingKey[File]("BuildEnv filter directory, used for buildEnv-specific filters.")
  lazy val filterFiles = taskKey[Seq[File]]("All filter files.")

  // unmanaged props: read from unmanaged filter files
  lazy val unmanagedProps = taskKey[Seq[(String, String)]]("Filter properties defined in filters.")

  // managed props: aggregate from project, system, buildEnv
  lazy val projectProps = taskKey[Seq[(String, String)]]("Project filter properties.")
  lazy val systemProps = taskKey[Seq[(String, String)]]("System filter properties.")
  lazy val envProps = taskKey[Seq[(String, String)]]("Environment filter properties.")
  lazy val managedProps = taskKey[Seq[(String, String)]]("Managed filter properties.")

  // props: aggregate from unmanaged, managed and extra
  lazy val extraProps = settingKey[Seq[(String, String)]]("Extra filter properties.")
  lazy val props = taskKey[Seq[(String, String)]]("All filter properties.")

  lazy val filterResources = taskKey[Seq[(File, File)]]("task to filter all resources.")
}

object FilterPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = AllRequirements

  override def requires: Plugins = EnvPlugin

  object autoimport extends FilterKeys

  import FileFilter.globFilter
  import autoimport._

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    filterDirectoryName := "filters", // default name
    filterDirectory := sourceDirectory.value / filterDirectoryName.value, // default directory: src/filters
    envFilterDirectory := filterDirectory.value / buildEnv.value.toString, // env filter directory: src/filters/dev, src/filters/prod, .etc
    sourceDirectories in filterFiles := Seq(envFilterDirectory.value),
    includeFilter in filterFiles := "*.properties" | "*.xml", // filter .properties and .xml
    excludeFilter in filterFiles := HiddenFileFilter, // do not filter hidden files
    filterFiles := collectFiles(
      sourceDirectories in filterFiles,
      includeFilter in filterFiles,
      excludeFilter in filterFiles
    ).value,
    unmanagedProps := {
      val log = streams.value.log
      filterFiles.value
        .foldLeft(Seq.empty[(String, String)])((acc, file) => acc ++ properties(log, file).asScala.toSeq)
    },
    projectProps := {
      val result = Seq(
        "organization" -> organization.value,
        "name" -> name.value,
        "description" -> description.value,
        "version" -> version.value,
        "scalaVersion" -> scalaVersion.value,
        "sbtVersion" -> sbtVersion.value
      )
      streams.value.log.debug(s"read project properties: ${result.mkString("\n")}")
      result
    },
    systemProps := {
      val result = System.getProperties.stringPropertyNames.asScala.toSeq map (k => k -> System.getProperty(k))
      streams.value.log.debug(s"read system properties: ${result.mkString("\n")}")
      result
    },
    envProps := {
      val result = System.getenv.asScala.toSeq
      streams.value.log.debug(s"read environment properties: ${result.mkString("\n")}")
      result
    },
    managedProps := projectProps.value ++ systemProps.value ++ envProps.value,
    extraProps := Nil,
    props := (managedProps.value ++ unmanagedProps.value ++ extraProps.value),
    includeFilter in filterResources := (includeFilter in filterFiles).value,
    excludeFilter in filterResources := (excludeFilter in filterFiles).value,
    // invalidate copyResources cache every time
    copyResources in Compile := {
      val s = (streams in (Compile, copyResources)).value
      val cacheFile = s.cacheDirectory / "copy-resources"
      s.log.debug(s"Invalidate copyResources cache: $cacheFile.")
      doClean(Seq(cacheFile), Seq.empty)
      s.log.debug(s"Restart copyResources.")
      (copyResources in Compile).value
    },
    // make filterResources triggered by copyResources
    filterResources := filter(copyResources in Compile, filterResources)
      .triggeredBy(copyResources in Compile)
      .value,
    // make packageBin use the filtered resources
    (packageBin in Compile) := (packageBin in Compile).dependsOn(filterResources).value
  )

  private def filter(resources: TaskKey[Seq[(File, File)]], task: TaskKey[Seq[(File, File)]]) =
    (streams, resources, includeFilter in task, excludeFilter in task, props) map {
      (streams, resources, incl, excl, props) =>
        val filtered = resources.filter(r => incl.accept(r._1) && !excl.accept(r._1) && !r._1.isDirectory)
        Filter.filterFiles(streams.log, filtered.map(_._2), props.toMap)
        resources
    }

  private def properties(log: Logger, path: File): Properties = {
    val props = new Properties
    IO.load(props, path)
    props
  }
}

object Filter {

  import java.io.{BufferedReader, FileReader, PrintWriter}

  import util.matching.Regex._

  val pattern = """((?:\\?)\$\{.+?\})""".r

  def filterFiles(log: Logger, files: Seq[File], props: Map[String, String]) {
    IO.withTemporaryDirectory { dir =>
      files.foreach { src =>
        log.debug("Filtering %s" format src.absolutePath)
        val dest = new File(dir, src.getName)
        val out = new PrintWriter(dest)
        val in = new BufferedReader(new FileReader(src))
        IO.foreachLine(in) { line =>
          IO.writeLines(out, Seq(filterLine(line, props)))
        }
        in.close()
        out.close()
        IO.copyFile(dest, src, preserveLastModified = true)
      }
    }
  }

  private def replacer(props: Map[String, String]) = (m: Match) => {
    m.matched match {
      case s if s.startsWith("\\") => Some("""\$\{%s\}""" format s.substring(3, s.length - 1))
      case s                       => props.get(s.substring(2, s.length - 1))
    }
  }

  private def filterLine(line: String, props: Map[String, String]) = pattern.replaceSomeIn(line, replacer(props))
}
