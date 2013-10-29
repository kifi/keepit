package com.keepit.search.index

import java.io.{FileInputStream, FileOutputStream, File}
import com.keepit.common.store._
import org.apache.commons.compress.archivers.tar._
import com.keepit.common.IO
import org.apache.lucene.store.{RAMDirectory, MMapDirectory, Directory}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.S3Bucket
import com.keepit.common.logging.Logging

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

  private var shouldBackup: Boolean = false
  def scheduleBackup() = shouldBackup = true
  def cancelBackup() = shouldBackup = false
  def processBackup() = if (shouldBackup) {
    val dir = getLocalDirectory()
    val tarFile = new File(dir, dir.getName + ".tar")
    val tarOut = new TarArchiveOutputStream(new FileOutputStream(tarFile))
    IO.addToArchive(tarOut, dir)
    tarOut.close()
    saveArchive(tarFile)
    tarFile.delete()
  }

  def restoreFromBackup(): Unit = {
    val dir = getLocalDirectory()
    val tarFile = getArchive()
    val tarIn = new TarArchiveInputStream(new FileInputStream(tarFile))
    IO.deleteFile(dir)
    IO.extractArchive(tarIn, dir.getParentFile.getAbsolutePath)
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
  def scheduleBackup(): Unit = log.error(s"Cannot schedule backup of volatile index directory ${this.getLockID}")
  def cancelBackup(): Unit = log.error(s"Cannot cancel backup of volatile index directory ${this.getLockID}")
  def processBackup(): Unit = log.error(s"Cannot process backup of volatile index directory ${this.getLockID}")
  def restoreFromBackup(): Unit = log.error(s"Cannot restore volatile index directory ${this.getLockID}")
}

class S3IndexStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val inbox: File) extends S3FileStore[IndexDirectory] with IndexStore {
  def idToKey(indexDirectory: IndexDirectory): String = indexDirectory.getLockID
  val useCompression = true
}

class LocalIndexStoreImpl(val inbox: File) extends LocalFileStore[IndexDirectory] with IndexStore
