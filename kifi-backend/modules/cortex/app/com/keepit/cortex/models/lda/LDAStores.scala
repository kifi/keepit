package com.keepit.cortex.models.lda

import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.S3Bucket
import com.keepit.cortex.store._
import com.keepit.common.logging.AccessLog
import com.keepit.cortex._
import com.keepit.cortex.MiscPrefix
import com.keepit.model.NormalizedURI
import com.keepit.common.db.Id
import com.keepit.common.store.S3JsonStore
import play.api.libs.json.Format

trait LDAModelStore extends StatModelStore[DenseLDA]

class S3LDAModelStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
    extends S3StatModelStore[DenseLDA] with LDAModelStore {
  val formatter = DenseLDAFormatter
  override val prefix = ModelStorePrefix.denseLDA
}

class InMemoryLDAModelStore extends InMemoryStatModelStore[DenseLDA] with LDAModelStore {
  val formatter = DenseLDAFormatter
}

trait LDAURIFeatureStore extends FloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, DenseLDA]

class S3BlobLDAURIFeatureStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
    extends S3BlobFloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, DenseLDA] with LDAURIFeatureStore {
  val prefix = FeatureStorePrefix.URIFeature.denseLDA
}

class InMemoryLDAURIFeatureStore extends InMemoryFloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, DenseLDA] with LDAURIFeatureStore

trait LDAURIFeatureCommitStore extends CommitInfoStore[NormalizedURI, DenseLDA]

class S3LDAURIFeatureCommitStore(bucketName: S3Bucket,
    amazonS3Client: AmazonS3,
    accessLog: AccessLog) extends S3CommitInfoStore[NormalizedURI, DenseLDA](bucketName, amazonS3Client, accessLog) with LDAURIFeatureCommitStore {
  val prefix = CommitInfoStorePrefix.URIFeature.denseLDA
}

class InMemoryLDAURIFeatureCommitStore extends InMemoryCommitInfoStore[NormalizedURI, DenseLDA] with LDAURIFeatureCommitStore

trait LDATopicWordsStore extends VersionedStore[String, DenseLDA, DenseLDATopicWords]

class S3LDATopicWordsStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val formatter: Format[DenseLDATopicWords] = DenseLDATopicWordsFormmater)
    extends S3JsonStore[VersionedStoreKey[String, DenseLDA], DenseLDATopicWords]
    with VersionedS3Store[String, DenseLDA, DenseLDATopicWords] with LDATopicWordsStore {
  val prefix: String = MiscPrefix.LDA.topicWordsFolder
  override def keyPrefix() = prefix
  override def idToKey(id: VersionedStoreKey[String, DenseLDA]) = "%s%s.json".format(prefix, id.toKey)
}

class InMemoryLDATopicWordsStore extends VersionedInMemoryStore[String, DenseLDA, DenseLDATopicWords] with LDATopicWordsStore

trait LDAConfigStore extends VersionedStore[String, DenseLDA, LDATopicConfigurations]

class S3LDAConfigStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val formatter: Format[LDATopicConfigurations] = LDATopicConfigurations.format)
    extends S3JsonStore[VersionedStoreKey[String, DenseLDA], LDATopicConfigurations]
    with VersionedS3Store[String, DenseLDA, LDATopicConfigurations] with LDAConfigStore {
  val prefix: String = MiscPrefix.LDA.topicConfigsFolder
  override def keyPrefix() = prefix
  override def idToKey(id: VersionedStoreKey[String, DenseLDA]) = "%s%s.json".format(prefix, id.toKey)
}

class InMemoryLDAConfigStore extends VersionedInMemoryStore[String, DenseLDA, LDATopicConfigurations] with LDAConfigStore

