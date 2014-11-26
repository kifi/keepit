import java.io.File

import sbt.Keys._
import sbt._


object Marketing {
  val marketingDirectory = SettingKey[File]("marketing-directory")

  val gulpCommands = Seq(
    commands <++= marketingDirectory { base =>
      Seq("gulp", "bower", "npm").map(c => CommandHelpers.cmd("mkt-" + c, c, base))
    },
    commands <+= marketingDirectory { base => CommandHelpers.cmd("mkt", "gulp", base, List("release")) }
  )

}
