package com.keepit.common.store

import java.io.{ FileOutputStream, InputStream }

import com.amazonaws.services.s3.transfer.model.UploadResult
import com.google.inject.Singleton
import play.api.libs.Files.TemporaryFile

import scala.collection.mutable
import scala.concurrent.Future

@Singleton
class FakeKeepImageStore(s3ImageConfig: S3ImageConfig) extends KeepImageStore {

  val cache = mutable.HashMap[String, TemporaryFile]()

  def put(key: String, is: InputStream, contentLength: Int, mimeType: String): Future[UploadResult] = {
    val tf = TemporaryFile(prefix = "test-file", suffix = ".png")
    // Intentionally does not write the data to the actual file. If this is needed, please add a mutable flag in this class.
    val result = new UploadResult()
    result.setBucketName(s3ImageConfig.bucketName)
    result.setKey(key)
    cache.put(key, tf)
    is.close()
    Future.successful(result)
  }
  def get(key: String): Future[TemporaryFile] = {
    cache.get(key) match {
      case Some(f) => Future.successful(f)
      case None => Future.failed(new RuntimeException("Key not there!"))
    }
  }

  def all: Map[String, TemporaryFile] = cache.toMap
}

