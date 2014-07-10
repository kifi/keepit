package com.keepit.abook.store

import com.keepit.common.db.{ State, States, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.common.store._
import com.keepit.model._
import com.amazonaws.services.s3._
import play.api.libs.json.{ JsArray, JsValue, Format }

trait ABookRawInfoStore extends ObjectStore[String, ABookRawInfo]

class S3ABookRawInfoStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog,
  val formatter: Format[ABookRawInfo] = ABookRawInfo.format)
    extends S3JsonStore[String, ABookRawInfo] with ABookRawInfoStore

class InMemoryABookRawInfoStoreImpl extends InMemoryObjectStore[String, ABookRawInfo] with ABookRawInfoStore
