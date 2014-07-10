package com.keepit.cortex.models.word2vec

import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.S3Bucket
import com.keepit.cortex._
import com.keepit.cortex.store._
import com.keepit.model.NormalizedURI

/**
 * model store
 */
trait Word2VecStore extends StatModelStore[Word2Vec]

class S3Word2VecStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
    extends S3StatModelStore[Word2Vec] with Word2VecStore {
  val formatter = Word2VecFormatter
  override val prefix = ModelStorePrefix.word2vec
}

class InMemoryWord2VecStore extends InMemoryStatModelStore[Word2Vec] with Word2VecStore {
  val formatter = Word2VecFormatter
}

/**
 * feature store
 */
trait Word2VecURIFeatureStore extends FloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, Word2Vec]

class S3BlobWord2VecURIFeatureStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
    extends S3BlobFloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, Word2Vec] with Word2VecURIFeatureStore {
  val prefix = FeatureStorePrefix.URIFeature.word2vec
}

class InMemoryWord2VecURIFeatureStore extends InMemoryFloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, Word2Vec] with Word2VecURIFeatureStore

/**
 * commit store
 */
trait Word2VecURIFeatureCommitStore extends CommitInfoStore[NormalizedURI, Word2Vec]

class S3Word2VecURIFeatureCommitStore(bucketName: S3Bucket,
    amazonS3Client: AmazonS3,
    accessLog: AccessLog) extends S3CommitInfoStore[NormalizedURI, Word2Vec](bucketName, amazonS3Client, accessLog) with Word2VecURIFeatureCommitStore {
  val prefix = CommitInfoStorePrefix.URIFeature.word2vec
}

class InMemoryWord2VecURIFeatureCommitStore extends InMemoryCommitInfoStore[NormalizedURI, Word2Vec] with Word2VecURIFeatureCommitStore

/**
 * rich feature store
 */
trait RichWord2VecURIFeatureStore extends BinaryFeatureStore[Id[NormalizedURI], NormalizedURI, Word2Vec, RichWord2VecURIFeature]

class S3RichWord2VecURIFeatureStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
    extends S3BlobBinaryFeatureStore[Id[NormalizedURI], NormalizedURI, Word2Vec, RichWord2VecURIFeature] with RichWord2VecURIFeatureStore {

  val formatter = RichWord2VecURIFeatureFormatter
  val prefix = FeatureStorePrefix.URIFeature.word2vec
}

class InMemoryRichWord2VecURIFeatureStore
  extends InMemoryBinaryFeatureStore[Id[NormalizedURI], NormalizedURI, Word2Vec, RichWord2VecURIFeature]
  with RichWord2VecURIFeatureStore
