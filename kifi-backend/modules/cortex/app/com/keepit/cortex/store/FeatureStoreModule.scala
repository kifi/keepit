package com.keepit.cortex.store

import com.google.inject.{Provides, Singleton}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.ProdStoreModule
import com.keepit.cortex.models.lda._
import com.keepit.common.store.S3Bucket
import play.api.Play._
import com.keepit.common.store.DevStoreModule
import com.keepit.cortex.models.lda.S3LDAModelStore
import net.codingwell.scalaguice.ScalaModule

trait FeatureStoreModule extends ScalaModule

case class FeatureProdStoreModule() extends ProdStoreModule with FeatureStoreModule{
  def configure(){}

  @Singleton
  @Provides
  def ldaURIFeatureStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAURIFeatureStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.cortex.bucket").get)
    new S3BlobLDAURIFeatureStore(bucketName, amazonS3Client, accessLog)
  }
}

case class FeatureDevStoreModule() extends DevStoreModule(FeatureProdStoreModule()) with FeatureStoreModule{
  def configure(){}

  @Singleton
  @Provides
  def ldaURIFeatureStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAURIFeatureStore = {
    whenConfigured("amazon.s3.cortex.bucket")(
      prodStoreModule.ldaURIFeatureStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryLDAURIFeatureStore)
  }
}
