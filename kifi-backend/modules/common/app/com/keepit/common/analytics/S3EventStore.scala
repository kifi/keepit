package com.keepit.common.analytics

import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.db.ExternalId
import com.keepit.common.store._
import play.api.libs.json.Format
import com.keepit.serializer.EventSerializer

trait EventStore extends ObjectStore[ExternalId[Event], Event]

class S3EventStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val formatter: Format[Event] = new EventSerializer())
  extends S3ObjectStore[ExternalId[Event], Event] with EventStore

class InMemoryS3EventStoreImpl extends InMemoryObjectStore[ExternalId[Event], Event] with EventStore
