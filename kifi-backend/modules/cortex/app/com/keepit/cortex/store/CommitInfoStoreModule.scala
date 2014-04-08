package com.keepit.cortex.store

import com.google.inject.{Provides, Singleton}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.ProdStoreModule
import com.keepit.cortex.models.lda._
import com.keepit.common.store.S3Bucket
import play.api.Play._
import com.keepit.common.store.DevStoreModule
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.store.{StoreModule, ProdOrElseDevStoreModule}

trait CommitInfoStoreModule extends StoreModule

case class CommitInfoProdStoreModule() extends CommitInfoStoreModule{
  def configure(){}

  @Singleton
  @Provides
  def denseLDACommitInfoStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAURIFeatureCommitStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.cortex.bucket").get)
    new S3LDAURIFeatureCommitStore(bucketName, amazonS3Client, accessLog)
  }

}

case class CommitInfoDevStoreModule() extends ProdOrElseDevStoreModule[CommitInfoProdStoreModule](CommitInfoProdStoreModule()) with CommitInfoStoreModule{
  def configure(){}

  @Singleton
  @Provides
  def denseLDACommitInfoStore(amazonS3Client: AmazonS3, accessLog: AccessLog) ={
    whenConfigured("amazon.s3.cortex.bucket")(
      prodStoreModule.denseLDACommitInfoStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryLDAURIFeatureCommitStore)
  }
}
