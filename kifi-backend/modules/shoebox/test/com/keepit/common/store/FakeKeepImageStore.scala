package com.keepit.common.store

import java.io.{ FileOutputStream, InputStream }

import com.amazonaws.services.s3.transfer.model.UploadResult
import play.api.libs.Files.TemporaryFile

import scala.collection.mutable
import scala.concurrent.Future

class FakeKeepImageStore(s3ImageConfig: S3ImageConfig) extends KeepImageStore {

  val cache = mutable.HashMap[String, TemporaryFile]()

  def put(key: String, is: InputStream, contentLength: Int, mimeType: String): Future[UploadResult] = {
    val tf = TemporaryFile()
    try {
      val result = new UploadResult()
      result.setBucketName(s3ImageConfig.bucketName)
      result.setKey(key)
      cache.put(key, tf)
      Future.successful(result)
    }
  }
  def get(key: String): Future[TemporaryFile] = {
    cache.get(key) match {
      case Some(f) => Future.successful(f)
      case None => Future.failed(new RuntimeException("Key not there!"))
    }
  }
}

