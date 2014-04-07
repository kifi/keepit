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

case class StatModelProdStoreModule() extends ProdStoreModule {
  def configure(){}

  @Singleton
  @Provides
  def ldaModelStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAModelStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.cortex.bucket").get)
    new S3LDAModelStore(bucketName, amazonS3Client, accessLog)
  }
}

case class StatModelDevStoreModule() extends DevStoreModule(StatModelProdStoreModule()){
  def configure(){}

  @Singleton
  @Provides
  def ldaModelStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAModelStore = {
    whenConfigured("amazon.s3.cortex.bucket")(
      prodStoreModule.ldaModelStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryLDAModelStore)
  }
}
