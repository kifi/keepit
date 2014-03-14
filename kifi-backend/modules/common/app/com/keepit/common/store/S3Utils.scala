package com.keepit.common.store

import java.io.InputStream
import scala.util.Try
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.Logging

trait S3Helper { self:Logging =>

  def s3Client:AmazonS3

  def streamUpload(bucketName:String, key: String, is: InputStream, cacheControl:String = "", contentLength: Int = 0, label: String = "") = Try {
    log.info(s"[streamUpload($bucketName,$key)] contentLen=$contentLength label=$label")
    val om = new ObjectMetadata()
    om.setContentType("image/jpeg")
    if (contentLength > 0) {
      om.setContentLength(contentLength)
    }
    if (cacheControl.trim.nonEmpty) {
      om.setCacheControl(cacheControl)
    }
    s3Client.putObject(bucketName, key, is, om)
  }

}
