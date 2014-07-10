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
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.store.{ StoreModule, ProdOrElseDevStoreModule }
import com.keepit.cortex._

trait CommitInfoStoreModule extends StoreModule

case class CommitInfoProdStoreModule() extends CommitInfoStoreModule {
  def configure() {}

  @Singleton
  @Provides
  def denseLDACommitInfoStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAURIFeatureCommitStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3LDAURIFeatureCommitStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def word2vecCommitInfoStore(amazonS3Client: AmazonS3, accessLog: AccessLog): Word2VecURIFeatureCommitStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3Word2VecURIFeatureCommitStore(bucketName, amazonS3Client, accessLog)
  }

}

case class CommitInfoDevStoreModule() extends ProdOrElseDevStoreModule(CommitInfoProdStoreModule()) with CommitInfoStoreModule {
  def configure() {}

  @Singleton
  @Provides
  def denseLDACommitInfoStore(amazonS3Client: AmazonS3, accessLog: AccessLog) = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.denseLDACommitInfoStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryLDAURIFeatureCommitStore)
  }

  @Singleton
  @Provides
  def word2vecCommitInfoStore(amazonS3Client: AmazonS3, accessLog: AccessLog): Word2VecURIFeatureCommitStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.word2vecCommitInfoStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryWord2VecURIFeatureCommitStore)
  }

}
