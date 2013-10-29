package com.keepit

import org.apache.commons.compress.archivers.tar.{TarArchiveInputStream, TarArchiveEntry, TarArchiveOutputStream}
import java.io.{FileOutputStream, File}
import java.nio.file.{StandardCopyOption, CopyOption, Files}

package object common {

  /** Useful when wanting to side-effect (log, stats, etc) and return the original value.
    * Lets us rewrite things like:
    * {{{
    *   var someVal = func()
    *   log.info(someVal)
    *   someVal
    * }}}
    * as:
    * {{{
    *   func() tap log.info
    * }}}
    */
  implicit class KestrelCombinator[A](val a: A) extends AnyVal {
    def withSideEffect(fun: A => Unit): A = { fun(a); a }
    def tap(fun: A => Unit): A = withSideEffect(fun)

    def withComputation[B](fun: A => B): (A, B) = { val b = fun(a); (a, b) }
    def tapWith[B](fun: A => B): (A, B) = withComputation(fun)
  }

  implicit class ForkCombinator[A, B](val a: A) extends AnyVal {
    def fork(t: A => Boolean)(y: A => B, z: A => B) = {
      if (t(a)) y(a)
      else z(a)
    }
  }

  implicit class Recoverable[A](f: => A) {
    def recover(g: Throwable => A): A = {
      try { f }
      catch {
        case t: Throwable => g(t)
      }
    }
  }

  object IO {
    def copyFile(source: File, destination: String): Unit = {
      val target = new File(destination)
      if (target.exists()) deleteFile(target)
      Files.copy(source.toPath, target.toPath)
      if (source.isDirectory) source.listFiles().foreach(file => copyFile(file, source.getAbsolutePath + "/" + file.getName))
    }

    def deleteFile(file: File): Unit = {
      if (file.isDirectory) file.listFiles().foreach(deleteFile)
      file.delete()
    }

    def addToArchive(tarArchive: TarArchiveOutputStream, file: File, base: String = ""): Unit = {
      val entryName = base + file.getName
      val entry = new TarArchiveEntry(file, entryName)
      tarArchive.putArchiveEntry(entry)
      if (file.isFile) Files.copy(file.toPath, tarArchive)
      tarArchive.closeArchiveEntry()
      if (file.isDirectory) file.listFiles().foreach(addToArchive(tarArchive, _, entryName + "/"))
    }

    def extractArchive(tarArchive: TarArchiveInputStream, destination: String): Unit = {
      var entryOption = Option(tarArchive.getNextTarEntry)
      while (entryOption.isDefined) {
        entryOption.foreach { entry =>
          val file = new File(destination + "/" + entry.getName)
          if (entry.isDirectory) file.mkdirs()
          else Files.copy(entry.getFile.toPath, file.toPath)
        }
        entryOption = Option(tarArchive.getNextTarEntry)
      }
    }
  }
}
