package com.keepit.graph.manager

import java.io.File
import com.keepit.common.store._
import com.keepit.common.{ArchivedDirectory, BackedUpDirectory}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.Logging
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.S3Bucket
import org.apache.commons.io.FileUtils

trait GraphDirectory extends BackedUpDirectory {
  def temp: File
}
trait GraphStore extends ObjectStore[GraphDirectory, File]

class GraphDirectoryImpl(dir: File, store: GraphStore) extends ArchivedDirectory with GraphDirectory {
  def getDirectory(): File = dir
  protected def getArchive() = store.get(this).get
  protected def saveArchive(tarFile: File) = store += (this, tarFile)
  val temp = {
    val tempDir = new File(dir, "temp").getCanonicalFile
    FileUtils.deleteDirectory(tempDir)
    FileUtils.forceMkdir(tempDir)
    tempDir.deleteOnExit()
    tempDir
  }
}

class VolatileGraphDirectoryImpl extends GraphDirectory with Logging {
  def getDirectory(): File = throw new UnsupportedOperationException(s"Cannot get file for volatile graph directory ${this}.")
  def temp(): File = throw new UnsupportedOperationException(s"Cannot get temporary directory for volatile graph directory ${this}.")
  def scheduleBackup(): Unit = log.warn(s"Cannot schedule backup of volatile graph directory ${this}")
  def cancelBackup(): Unit = log.warn(s"Cannot cancel backup of volatile graph directory ${this}")
  def doBackup(): Boolean = false
  def restoreFromBackup(): Unit = log.warn(s"Cannot restore volatile graph directory ${this}")
}

case class GraphStoreInbox(dir: File) extends S3InboxDirectory

class S3GraphStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val inbox: GraphStoreInbox) extends S3FileStore[GraphDirectory] with GraphStore {
  def idToKey(graphDirectory: GraphDirectory): String = graphDirectory.getDirectory().getName + ".tar.gz"
}

class InMemoryGraphStoreImpl extends InMemoryFileStore[GraphDirectory] with GraphStore
