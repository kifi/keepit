package com.keepit.cortex.store

import com.google.inject.{ Provides, Singleton }
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.ProdStoreModule
import com.keepit.cortex.models.lda._
import com.keepit.cortex.models.word2vec._
import com.keepit.common.store.S3Bucket
import play.api.Play._
import com.keepit.common.store.DevStoreModule
import com.keepit.cortex.models.lda.S3LDAModelStore
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.store.{ StoreModule, ProdOrElseDevStoreModule }
import com.keepit.cortex._

trait FeatureStoreModule extends StoreModule

case class FeatureProdStoreModule() extends FeatureStoreModule {
  def configure() {}

  @Singleton
  @Provides
  def ldaURIFeatureStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAURIFeatureStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3BlobLDAURIFeatureStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def word2vecURIFeatureStore(amazonS3Client: AmazonS3, accessLog: AccessLog): Word2VecURIFeatureStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3BlobWord2VecURIFeatureStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def richWord2vecURIFeatureStore(amazonS3Client: AmazonS3, accessLog: AccessLog): RichWord2VecURIFeatureStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3RichWord2VecURIFeatureStore(bucketName, amazonS3Client, accessLog)
  }

}

case class FeatureDevStoreModule() extends ProdOrElseDevStoreModule(FeatureProdStoreModule()) with FeatureStoreModule {
  def configure() {}

  @Singleton
  @Provides
  def ldaURIFeatureStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAURIFeatureStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.ldaURIFeatureStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryLDAURIFeatureStore)
  }

  @Singleton
  @Provides
  def word2vecURIFeatureStore(amazonS3Client: AmazonS3, accessLog: AccessLog): Word2VecURIFeatureStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.word2vecURIFeatureStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryWord2VecURIFeatureStore)
  }

  @Singleton
  @Provides
  def richWord2vecURIFeatureStore(amazonS3Client: AmazonS3, accessLog: AccessLog): RichWord2VecURIFeatureStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.richWord2vecURIFeatureStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryRichWord2VecURIFeatureStore)
  }

}
