package com.keepit.graph.manager

import java.io.File
import com.keepit.common.store._
import com.keepit.common.{ ArchivedDirectory }
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.{ Logging, AccessLog }
import com.keepit.common.time._
import com.keepit.common.store.S3Bucket
import org.apache.commons.io.FileUtils

trait GraphDirectory {
  def asFile(): Option[File]
}

trait ArchivedGraphDirectory extends ArchivedDirectory with Logging { self: GraphDirectory =>
  def asFile() = Some(getDirectory())
  protected def store: GraphStore
  protected def getArchive() = store.syncGet(this).get
  protected def saveArchive(tarFile: File) = store += (this, tarFile)
  def init(): Unit = {
    val dir = getDirectory()
    if (!dir.exists()) {
      try {
        val t1 = currentDateTime.getMillis
        restoreFromBackup()
        val t2 = currentDateTime.getMillis
        log.info(s"ArchivedGraphDirectory ${dir.getPath()} was restored from GraphStore in ${(t2 - t1) / 1000} seconds")
      } catch {
        case e: Exception => {
          log.error(s"Could not restore $dir from GraphStore}", e)
          FileUtils.deleteDirectory(dir)
          FileUtils.forceMkdir(dir)
        }
      }
    }
  }
}

trait GraphStore extends ObjectStore[ArchivedGraphDirectory, File]

case class GraphStoreInbox(dir: File) extends S3InboxDirectory

class S3GraphStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val inbox: GraphStoreInbox) extends S3FileStore[ArchivedGraphDirectory] with GraphStore {
  def idToKey(graphDirectory: ArchivedGraphDirectory): String = graphDirectory.getDirectory().getName + ".tar.gz"
}

class InMemoryGraphStoreImpl extends InMemoryFileStore[ArchivedGraphDirectory] with GraphStore
