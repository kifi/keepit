import java.io.File

import sbt.Keys._
import sbt._


object Frontend {
  val angularDirectory = SettingKey[File]("angular-directory")

  private def cmd(name: String, command: String, base: File, namedArgs: List[String] = Nil): Command = {
    Command.args(name, "<" + name + "-command>") { (state, args) =>
      val exitCode = Process(command :: (namedArgs ++ args.toList), base).!;
      if (exitCode!=0) throw new Exception(s"Command '${(command :: (namedArgs ++ args.toList)).mkString(" ")}' failed with exit code $exitCode")
      state
    }
  }

  val gulpCommands = Seq(
    commands <++= angularDirectory { base =>
      Seq("gulp", "bower", "npm").map(c => cmd(c, c, base))
    },
    commands <+= angularDirectory { base => cmd("ng", "gulp", base, List("release")) }
  )

}
