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

trait BackedUpDirectory {
  def scheduleBackup(): Unit
  def cancelBackup(): Unit
  def processBackup(): Unit
  def restoreFromBackup(): Unit
}

trait ArchivedDirectory extends BackedUpDirectory {
  protected def getLocalDirectory(): File
  protected def getArchive(): File
  protected def saveArchive(tarFile: File): Unit

  private val shouldBackup = new AtomicBoolean(false)
  def scheduleBackup() = shouldBackup.set(true)
  def cancelBackup() = shouldBackup.set(false)
  def processBackup() = if (shouldBackup.getAndSet(false)) {
    val dir = getLocalDirectory()
    val tarFile = new File(dir, dir.getName + ".tar")
    val tarOut = new TarArchiveOutputStream(new FileOutputStream(tarFile))
    try {
      IO.addToArchive(tarOut, dir)
    } finally {
      tarOut.close()
    }
    saveArchive(tarFile)
    tarFile.delete()
  }

  def restoreFromBackup(): Unit = {
    val dir = getLocalDirectory()
    val tarFile = getArchive()
    val tarIn = new TarArchiveInputStream(new FileInputStream(tarFile))
    try {
      IO.deleteFile(dir)
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
  def processBackup(): Unit = log.warn(s"Cannot process backup of volatile index directory ${this.getLockID}")
  def restoreFromBackup(): Unit = log.warn(s"Cannot restore volatile index directory ${this.getLockID}")
}

class S3IndexStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val inbox: File) extends S3FileStore[IndexDirectory] with IndexStore {
  def idToKey(indexDirectory: IndexDirectory): String = indexDirectory.getLockID
  val useCompression = true
}

class LocalIndexStoreImpl(val inbox: File) extends LocalFileStore[IndexDirectory] with IndexStore
