package sbtbuildenv

import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin

object EnvPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = AllRequirements

  override def requires: Plugins = JvmPlugin

  object autoImport {
    object BuildEnv extends Enumeration {
      val dev, test, staging, prod = Value
    }
    val buildEnv: SettingKey[BuildEnv.Value] =
      settingKey[BuildEnv.Value]("the current build environment")
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] =
    Seq(
      buildEnv := {
        val log = sLog.value
        val defaultEnv = BuildEnv.dev
        sys.props
          .get("buildEnv")
          .orElse(sys.env.get("BUILD_ENV"))
          .map {
            case "dev"     => BuildEnv.dev
            case "test"    => BuildEnv.test
            case "staging" => BuildEnv.staging
            case "prod"    => BuildEnv.prod
            case x =>
              log.warn(s"no match rule for buildEnv $x, fallback to $defaultEnv")
              defaultEnv
          }
          .getOrElse {
            log.warn(s"cannot find buildEnv, fallback to $defaultEnv")
            defaultEnv
          }
      },
      onLoadMessage := {
        val defaultMessage = onLoadMessage.value
        s"""|$defaultMessage
            |Current build environment: ${buildEnv.value}""".stripMargin
      }
    )
}
