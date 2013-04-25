package com.keepit.common.analytics.reports

import scala.collection.mutable.{Map => MutableMap}
import com.keepit.common.db.Id
import com.keepit.serializer.CompleteReportSerializer
import com.keepit.inject._
import play.api.Play.current
import com.amazonaws.auth._
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import java.io.{InputStream, ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.lang.UnsupportedOperationException
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.S3Object
import com.keepit.common.store._
import play.api.libs.json.Format
import com.keepit.common.store.InMemoryObjectStore

trait ReportStore extends ObjectStore[String, Report] {
  def getReports(): List[String] = Nil
}

class S3ReportStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val formatter: Format[Report] = new CompleteReportSerializer())
  extends S3ObjectStore[String, Report] with ReportStore {
  override def idToBJsonKey(id: String): String = id

  override def getReports(): List[String] = {
    import scala.collection.JavaConversions._
    amazonS3Client.listObjects(bucketName).getObjectSummaries.map(o => o.getKey).toList
  }
}

class InMemoryReportStoreImpl extends InMemoryObjectStore[String, Report] with ReportStore {
  override def getReports(): List[String] = localStore.keySet.toList
}
