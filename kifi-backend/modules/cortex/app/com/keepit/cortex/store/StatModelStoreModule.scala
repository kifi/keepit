package com.keepit.cortex.store

import com.google.inject.{Provides, Singleton}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.ProdStoreModule
import com.keepit.cortex.models.lda._
import com.keepit.common.store.S3Bucket
import play.api.Play._
import com.keepit.common.store.DevStoreModule
import com.keepit.cortex.core._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.cortex._
import com.keepit.common.store.{StoreModule, ProdOrElseDevStoreModule}

trait StatModelStoreModule extends StoreModule

case class StatModelProdStoreModule() extends StatModelStoreModule{
  def configure(){}

  @Singleton
  @Provides
  def ldaModelStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAModelStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3LDAModelStore(bucketName, amazonS3Client, accessLog)
  }
}

case class StatModelDevStoreModule() extends ProdOrElseDevStoreModule[StatModelProdStoreModule](StatModelProdStoreModule()) with StatModelStoreModule{
  def configure(){}

  @Singleton
  @Provides
  def ldaModelStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAModelStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.ldaModelStore(amazonS3Client, accessLog)
    ) getOrElse {
      val store = new InMemoryLDAModelStore
      val version = ModelVersions.denseLDAVersion
      val mapper = Map("scala" -> Array(1f, 0f), "kifi" -> Array(1f, 0f), "food" -> Array(0f, 1f), "recipe" -> Array(0f, 1f))
      val dim = 2
      val lda = DenseLDA(dim, mapper)
      store.+=(version, lda)
      store
    }
  }
}
