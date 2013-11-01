package com.keepit

import org.apache.commons.compress.archivers.tar.{TarArchiveInputStream, TarArchiveEntry, TarArchiveOutputStream}
import java.io._
import org.apache.commons.io.{IOUtils, FileUtils}

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

  import com.keepit.common.concurrent.ExecutionContext
  import scala.concurrent.Future
  implicit class ImmediateMap[T](f: => Future[T]) {
    def imap[S](g: T => S): Future[S] = {
      f.map(g)(ExecutionContext.immediate)
    }
  }

  object IO {

    def addToArchive(tarArchive: TarArchiveOutputStream, file: File, base: String = ""): Unit = {
      val entryName = base + file.getName
      if (file.isFile) {
        val entry = new TarArchiveEntry(file, entryName)
        entry.setSize(file.length())
        tarArchive.putArchiveEntry(entry)
        FileUtils.copyFile(file, tarArchive)
        tarArchive.closeArchiveEntry()
      } else
        file.listFiles().foreach(addToArchive(tarArchive, _, entryName + "/"))
    }

    def extractArchive(tarArchive: TarArchiveInputStream, destination: String): Unit = {
      var entryOption = Option(tarArchive.getNextTarEntry)
      while (entryOption.isDefined) {
        entryOption.foreach { entry =>
          val out = FileUtils.openOutputStream(new File(destination, entry.getName))
          try {
            IOUtils.copyLarge(tarArchive, out, 0, entry.getSize)
          } finally {
            out.close()
          }
        }
        entryOption = Option(tarArchive.getNextTarEntry)
      }
    }
  }
}
