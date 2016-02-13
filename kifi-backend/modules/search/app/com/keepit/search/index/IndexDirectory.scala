package com.keepit.search.index

import java.io.File
import com.keepit.common.store._
import com.keepit.common.{ ArchivedDirectory, BackedUpDirectory }
import org.apache.lucene.store.{ RAMDirectory, MMapDirectory, Directory }
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.S3Bucket
import com.keepit.common.logging.Logging
import com.keepit.common.logging.AccessLog

trait IndexDirectory extends Directory with BackedUpDirectory {
  def asFile(): Option[File]
}

trait IndexStore extends ObjectStore[ArchivedIndexDirectory, File]

class ArchivedIndexDirectory(dir: File, protected val tempDir: File, store: IndexStore) extends MMapDirectory(dir) with ArchivedDirectory with IndexDirectory {
  def asFile() = Some(dir)
  protected def getArchive() = store.syncGet(this).get
  protected def saveArchive(tarFile: File) = store += (this, tarFile)
}

class VolatileIndexDirectory extends RAMDirectory with IndexDirectory with Logging {
  def asFile() = None
  def scheduleBackup(): Unit = log.warn(s"Cannot schedule backup of volatile index directory ${this.getLockID}")
  def cancelBackup(): Unit = log.warn(s"Cannot cancel backup of volatile index directory ${this.getLockID}")
  def doBackup(): Boolean = false
  def restoreFromBackup(): Unit = log.warn(s"Cannot restore volatile index directory ${this.getLockID}")
}

case class IndexStoreInbox(dir: File) extends S3InboxDirectory

class S3IndexStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val inbox: IndexStoreInbox) extends S3FileStore[ArchivedIndexDirectory] with IndexStore {
  def idToKey(indexDirectory: ArchivedIndexDirectory): String = indexDirectory.getDirectory().getName + ".tar.gz"
}

class InMemoryIndexStoreImpl extends InMemoryFileStore[ArchivedIndexDirectory] with IndexStore
