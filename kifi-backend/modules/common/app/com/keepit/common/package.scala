package com.keepit

import org.apache.commons.compress.archivers.tar.{ TarArchiveInputStream, TarArchiveEntry, TarArchiveOutputStream }
import java.io._
import org.apache.commons.io.{ IOUtils, FileUtils }
import scala.collection.mutable.{ SynchronizedSet, HashSet, Set => MutableSet, ListBuffer }
import java.util.zip.{ GZIPInputStream, GZIPOutputStream }
import java.util.concurrent.atomic.AtomicBoolean

package object common {

  /**
   * Useful when wanting to side-effect (log, stats, etc) and return the original value.
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
      val entry = new TarArchiveEntry(file, entryName)
      tarArchive.putArchiveEntry(entry)
      entry.setSize(file.length())
      if (file.isFile) { FileUtils.copyFile(file, tarArchive) }
      tarArchive.closeArchiveEntry()
      if (file.isDirectory) file.listFiles().foreach(addToArchive(tarArchive, _, entryName + "/"))
    }

    def extractArchive(tarArchive: TarArchiveInputStream, destDir: String): Seq[File] = {
      val files = new ListBuffer[File]
      var entryOption = Option(tarArchive.getNextTarEntry)
      while (entryOption.isDefined) {
        entryOption.foreach { entry =>
          val file = new File(destDir, entry.getName)
          files.append(file)
          if (entry.isFile) {
            val out = FileUtils.openOutputStream(file)
            try { IOUtils.copyLarge(tarArchive, out, 0, entry.getSize) }
            catch { case e: Throwable => files.foreach(_.delete()); throw e } // directories created by FileUtils.openOutputStream may not be cleaned up
            finally { out.close() }
          } else {
            try { FileUtils.forceMkdir(file) }
            catch { case e: Throwable => files.foreach(_.delete()); throw e } // directories created by FileUtils.forceMkdir may not be cleaned up
          }
        }
        entryOption = Option(tarArchive.getNextTarEntry)
      }
      files.toList
    }

    def compress(file: File, outputStream: OutputStream): Unit = {
      val gZip = new GZIPOutputStream(outputStream)
      val compressedTarArchive = new TarArchiveOutputStream(gZip)
      compressedTarArchive.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
      addToArchive(compressedTarArchive, file)
      compressedTarArchive.finish()
      gZip.finish()
    }

    def compress(file: File, destDir: String): File = {
      val tarGz = new File(destDir, file.getName + ".tar.gz")
      val out = FileUtils.openOutputStream(tarGz)
      try { compress(file, out) }
      catch { case e: Throwable => tarGz.delete(); throw e }
      finally { out.close() }
      tarGz
    }

    def uncompress(tarGzStream: InputStream, destDir: String): Unit = {
      val uncompressedTarArchive = new TarArchiveInputStream(new GZIPInputStream(tarGzStream))
      extractArchive(uncompressedTarArchive, destDir)
    }

    def uncompress(tarGz: File, destDir: String): Unit = {
      val inputStream = FileUtils.openInputStream(tarGz)
      try { uncompress(inputStream, destDir) }
      finally { inputStream.close() }
    }
  }

  trait BackedUpDirectory {
    def scheduleBackup(): Unit
    def cancelBackup(): Unit
    def doBackup(): Boolean
    def restoreFromBackup(): Unit
  }

  trait ArchivedDirectory extends BackedUpDirectory {
    def getDirectory(): File
    protected def getArchive(): File
    protected def saveArchive(archive: File): Unit
    protected def tempDir(): File

    private val shouldBackup = new AtomicBoolean(false)
    def scheduleBackup() = shouldBackup.set(true)
    def cancelBackup() = shouldBackup.set(false)
    def doBackup() = if (shouldBackup.getAndSet(false)) {
      val dir = getDirectory()
      val tarGz = IO.compress(dir, tempDir.getCanonicalPath)
      saveArchive(tarGz)
      tarGz.delete()
      true
    } else false

    def restoreFromBackup(): Unit = {
      val dir = getDirectory()
      val tarGz = getArchive()
      FileUtils.deleteDirectory(dir)
      IO.uncompress(tarGz, dir.getParentFile.getAbsolutePath)
      tarGz.delete()
    }
  }

  trait Register[V] extends MutableSet[V]
  class HashSetRegister[V] extends HashSet[V] with SynchronizedSet[V] with Register[V]

  object PriorityStreamAggregator {
    def aggregateBy[R, T](priorityStreams: Stream[R]*)(f: R => T)(implicit ord: Ordering[T]): Stream[R] = {
      priorityStreams.reduceLeft((aggregatedStream, nextStream) => aggregateBy(aggregatedStream, nextStream)(f))
    }

    private def aggregateBy[R, T](firstPriorityStream: Stream[R], secondPriorityStream: Stream[R])(f: R => T)(implicit ord: Ordering[T]): Stream[R] = {
      (firstPriorityStream.headOption, secondPriorityStream.headOption) match {
        case (_, None) => firstPriorityStream
        case (None, _) => secondPriorityStream
        case (Some(firstHead), Some(secondHead)) => {
          if (ord.lt(f(firstHead), f(secondHead))) secondHead #:: aggregateBy(firstPriorityStream, secondPriorityStream.tail)(f)
          else firstHead #:: aggregateBy(firstPriorityStream.tail, secondPriorityStream)(f) // stable
        }
      }
    }
  }
}
