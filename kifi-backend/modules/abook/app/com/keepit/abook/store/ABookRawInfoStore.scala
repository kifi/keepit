package com.keepit.abook.store


import com.keepit.common.db.{State, States, Id}
import com.keepit.common.store._
import com.keepit.model._
import com.amazonaws.services.s3._
import play.api.libs.json.{JsArray, JsValue, Format}
import com.keepit.common.store.S3Bucket

trait ABookRawInfoStore extends ObjectStore[String, ABookRawInfo]

class S3ABookRawInfoStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3,
                              val formatter: Format[ABookRawInfo] = ABookRawInfo.format)
  extends S3JsonStore[String, ABookRawInfo] with ABookRawInfoStore

class InMemoryABookRawInfoStoreImpl extends InMemoryObjectStore[String, ABookRawInfo] with ABookRawInfoStore
