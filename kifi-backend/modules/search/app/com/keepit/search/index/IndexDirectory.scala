package com.keepit.search.index

import java.io.File
import com.keepit.common.store._
import com.keepit.common.{ArchivedDirectory, BackedUpDirectory}
import org.apache.lucene.store.{RAMDirectory, MMapDirectory, Directory}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.S3Bucket
import com.keepit.common.logging.Logging
import com.keepit.common.logging.AccessLog

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
