package com.keepit.search.index

import java.io.File
import com.keepit.common.store._
import com.keepit.common.IO
import org.apache.lucene.store.{RAMDirectory, MMapDirectory, Directory}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.S3Bucket
import com.keepit.common.logging.Logging
import com.keepit.common.logging.AccessLog
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.io.FileUtils

trait BackedUpDirectory {
  def getDirectory(): File
  def scheduleBackup(): Unit
  def cancelBackup(): Unit
  def doBackup(): Boolean
  def restoreFromBackup(): Unit
}

trait ArchivedDirectory extends BackedUpDirectory {
  protected def getArchive(): File
  protected def saveArchive(archive: File): Unit

  private val shouldBackup = new AtomicBoolean(false)
  def scheduleBackup() = shouldBackup.set(true)
  def cancelBackup() = shouldBackup.set(false)
  def doBackup() = if (shouldBackup.getAndSet(false)) {
    val dir = getDirectory()
    val tarGz = IO.compress(dir)
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

trait IndexStore extends ObjectStore[IndexDirectory, File]

trait IndexDirectory extends Directory with BackedUpDirectory

class IndexDirectoryImpl(dir: File, store: IndexStore) extends MMapDirectory(dir) with ArchivedDirectory with IndexDirectory {
  protected def getArchive() = store.get(this).get
  protected def saveArchive(tarFile: File) = store += (this, tarFile)
}

class VolatileIndexDirectoryImpl extends RAMDirectory with IndexDirectory with Logging {
  def getDirectory(): File = throw new UnsupportedOperationException(s"Cannot get file for volatile directory ${this.getLockID}.")
  def scheduleBackup(): Unit = log.warn(s"Cannot schedule backup of volatile index directory ${this.getLockID}")
  def cancelBackup(): Unit = log.warn(s"Cannot cancel backup of volatile index directory ${this.getLockID}")
  def doBackup(): Boolean = false
  def restoreFromBackup(): Unit = log.warn(s"Cannot restore volatile index directory ${this.getLockID}")
}

class S3IndexStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog) extends S3FileStore[IndexDirectory] with IndexStore {
  def idToKey(indexDirectory: IndexDirectory): String = indexDirectory.getDirectory().getName + ".tar.gz"
}

class InMemoryIndexStoreImpl extends InMemoryFileStore[IndexDirectory] with IndexStore
