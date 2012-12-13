package com.keepit.common.analytics

import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.db.ExternalId
import com.keepit.common.store.ObjectStore
import com.keepit.common.store.S3Bucket
import com.keepit.common.store.S3ObjectStore
import play.api.libs.json.Format
import com.keepit.serializer.EventSerializer

trait S3EventStore extends ObjectStore[ExternalId[Event], Event]

class S3EventStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val formatter: Format[Event] = new EventSerializer())
  extends S3ObjectStore[ExternalId[Event], Event] with S3EventStore