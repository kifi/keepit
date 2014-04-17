package com.keepit.cortex.models.word2vec

import com.keepit.cortex.store._
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.S3Bucket
import com.keepit.cortex._


trait Word2VecStore extends StatModelStore[Word2Vec]

class S3Word2VecStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
extends S3StatModelStore[Word2Vec] with Word2VecStore {
  val formatter = Word2VecFormatter
  override val prefix = ModelStorePrefix.word2vec
}

class InMemoryWord2VecStore extends InMemoryStatModelStore[Word2Vec] with Word2VecStore {
  val formatter = Word2VecFormatter
}
