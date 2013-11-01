package com.keepit.search.index

import java.io.{FileInputStream, FileOutputStream, File}
import com.keepit.common.store._
import org.apache.commons.compress.archivers.tar._
import com.keepit.common.IO
import org.apache.lucene.store.{RAMDirectory, MMapDirectory, Directory}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.S3Bucket
import com.keepit.common.logging.Logging
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.io.FileUtils

trait BackedUpDirectory {
  def scheduleBackup(): Unit
  def cancelBackup(): Unit
  def doBackup(): Boolean
  def restoreFromBackup(): Unit
}

trait ArchivedDirectory extends BackedUpDirectory {
  protected def getLocalDirectory(): File
  protected def getArchive(): File
  protected def saveArchive(tarFile: File): Unit

  private val shouldBackup = new AtomicBoolean(false)
  def scheduleBackup() = shouldBackup.set(true)
  def cancelBackup() = shouldBackup.set(false)
  def doBackup() = if (shouldBackup.getAndSet(false)) {
    val dir = getLocalDirectory()
    val tarFile = new File(FileUtils.getTempDirectory, dir.getName + ".tar")
    val tarStream = new FileOutputStream(tarFile)
    val tarOut = new TarArchiveOutputStream(tarStream)
    tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
    try {
      IO.addToArchive(tarOut, dir)
      tarOut.finish()
      saveArchive(tarFile)
    } finally {
      tarOut.close()
      tarFile.delete()
    }
    true
  } else false

  def restoreFromBackup(): Unit = {
    val dir = getLocalDirectory()
    val tarFile = getArchive()
    val tarIn = new TarArchiveInputStream(new FileInputStream(tarFile))
    try {
      FileUtils.deleteDirectory(dir)
      IO.extractArchive(tarIn, dir.getParentFile.getAbsolutePath)
    } finally {
      tarIn.close()
    }
  }
}

trait IndexStore extends ObjectStore[IndexDirectory, File]

trait IndexDirectory extends Directory with BackedUpDirectory

class IndexDirectoryImpl(dir: File, store: IndexStore) extends MMapDirectory(dir) with ArchivedDirectory with IndexDirectory {
  protected def getLocalDirectory(): File = directory
  protected def getArchive() = store.get(this).get
  protected def saveArchive(tarFile: File) = store += (this, tarFile)
}

class VolatileIndexDirectoryImpl extends RAMDirectory with IndexDirectory with Logging {
  def scheduleBackup(): Unit = log.warn(s"Cannot schedule backup of volatile index directory ${this.getLockID}")
  def cancelBackup(): Unit = log.warn(s"Cannot cancel backup of volatile index directory ${this.getLockID}")
  def doBackup(): Boolean = false
  def restoreFromBackup(): Unit = log.warn(s"Cannot restore volatile index directory ${this.getLockID}")
}

class S3IndexStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3) extends S3FileStore[IndexDirectory] with IndexStore {
  def idToKey(indexDirectory: IndexDirectory): String = { val id = indexDirectory.getLockID; println("############### INDEX KEY: " + id); id }
  val useCompression = true
}

class InMemoryIndexStoreImpl extends InMemoryFileStore[IndexDirectory] with IndexStore
