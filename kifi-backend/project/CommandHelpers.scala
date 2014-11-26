import java.io.File

import sbt._

object CommandHelpers {
  // helper to create an SBT Command that runs a process <command> in the specified directory <base> with
  // the provided arguments <namedArgs>
  def cmd(name: String, command: String, base: File, namedArgs: List[String] = Nil): Command = {
    Command.args(name, "<" + name + "-command>") { (state, args) =>
      val exitCode = Process(command :: (namedArgs ++ args.toList), base).!
      if (exitCode != 0) throw new Exception(s"Command '${(command :: (namedArgs ++ args.toList)).mkString(" ")}' failed with exit code $exitCode")
      state
    }
  }
}
