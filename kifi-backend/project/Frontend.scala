import java.io.File

import sbt.Keys._
import sbt._


object Frontend {
  val angularDirectory = SettingKey[File]("angular-directory")

  val gulpCommands = Seq(
    commands <++= angularDirectory { base =>
      Seq("gulp", "bower", "npm").map(c => CommandHelpers.cmd("ng-" + c, c, base))
    },
    commands <+= angularDirectory { base => CommandHelpers.cmd("ng", "gulp", base, List("release")) }
  )

}
