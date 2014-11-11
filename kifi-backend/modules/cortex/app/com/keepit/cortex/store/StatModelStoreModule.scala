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
import com.keepit.cortex.core._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.cortex._
import com.keepit.common.store.{ StoreModule, ProdOrElseDevStoreModule }

trait StatModelStoreModule extends StoreModule

case class StatModelProdStoreModule() extends StatModelStoreModule {
  def configure() {}

  @Singleton
  @Provides
  def ldaModelStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAModelStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3LDAModelStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def word2vecStore(amazonS3Client: AmazonS3, accessLog: AccessLog): Word2VecStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3Word2VecStore(bucketName, amazonS3Client, accessLog)
  }
}

case class StatModelDevStoreModule() extends ProdOrElseDevStoreModule(StatModelProdStoreModule()) with StatModelStoreModule {
  def configure() {}

  val dim = 2
  val mapper = Map("scala" -> Array(1f, 0f), "kifi" -> Array(1f, 0f), "food" -> Array(0f, 1f), "recipe" -> Array(0f, 1f))

  val dim2 = 2
  val mapper2 = Map("ode" -> Array(1f, 0f), "pde" -> Array(1f, 0f), "bayes" -> Array(0f, 1f), "regression" -> Array(0f, 1f))

  val ldas = List(DenseLDA(dim, mapper), DenseLDA(dim2, mapper2))

  @Singleton
  @Provides
  def ldaModelStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAModelStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.ldaModelStore(amazonS3Client, accessLog)
    ) getOrElse {
        val store = new InMemoryLDAModelStore
        (ModelVersions.availableLDAVersions.take(2) zip ldas).foreach { case (version, lda) => store.+=(version, lda) }
        store
      }
  }

  @Singleton
  @Provides
  def word2vecStore(amazonS3Client: AmazonS3, accessLog: AccessLog): Word2VecStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.word2vecStore(amazonS3Client, accessLog)
    ) getOrElse {
        val store = new InMemoryWord2VecStore
        val version = ModelVersions.word2vecVersion
        val word2vec = Word2VecImpl(dim, mapper)
        store.+=(version, word2vec)
        store
      }
  }

}
