package com.keepit.cortex.models.lda

import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.S3Bucket
import com.keepit.cortex.store._
import com.keepit.cortex.ModelStorePrefix
import com.keepit.common.logging.AccessLog
import com.keepit.cortex.CommitInfoStorePrefix
import com.keepit.cortex.FeatureStorePrefix
import com.keepit.model.NormalizedURI
import com.keepit.common.db.Id

trait LDAModelStore extends StatModelStore[DenseLDA]

class S3LDAModelStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
extends S3StatModelStore[DenseLDA] with LDAModelStore {
  val formatter = DenseLDAFormatter
  override val prefix = ModelStorePrefix.denseLDA
}

class InMemoryLDAModelStore extends InMemoryStatModelStore[DenseLDA] with LDAModelStore{
  val formatter = DenseLDAFormatter
}

trait LDAURIFeatureStore extends FloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, DenseLDA]

class S3BlobLDAURIFeatureStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
  extends S3BlobFloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, DenseLDA] with LDAURIFeatureStore{
  val prefix = FeatureStorePrefix.URIFeature.denseLDA
}

class InMemoryLDAURIFeatureStore extends InMemoryFloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, DenseLDA] with LDAURIFeatureStore

trait LDAURIFeatureCommitStore extends CommitInfoStore[NormalizedURI, DenseLDA]

class S3LDAURIFeatureCommitStore(bucketName: S3Bucket,
  amazonS3Client: AmazonS3,
  accessLog: AccessLog) extends S3CommitInfoStore[NormalizedURI, DenseLDA](bucketName, amazonS3Client, accessLog) with LDAURIFeatureCommitStore{
  val prefix = CommitInfoStorePrefix.URIFeature.denseLDA
}

class InMemoryLDAURIFeatureCommitStore extends InMemoryCommitInfoStore[NormalizedURI, DenseLDA] with LDAURIFeatureCommitStore
