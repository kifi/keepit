package com.keepit.cortex.models.lda

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.S3Bucket
import com.keepit.cortex._
import com.keepit.cortex.core.ModelVersion
import play.api.Play.current
import com.keepit.common.store.ProdOrElseDevStoreModule
import com.keepit.common.store.StoreModule
import com.keepit.common.logging.Logging

trait LDAInfoStoreModule extends StoreModule {
  protected def generateDefaultConfig(dim: Int): LDATopicConfigurations = {
    val conf = (0 until dim).map { i => (i.toString, LDATopicConfiguration.default) }.toMap
    LDATopicConfigurations(conf)
  }
}

case class LDAInfoStoreProdModule() extends LDAInfoStoreModule with Logging {
  def configure() {}

  @Singleton
  @Provides
  def topicWordsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDATopicWordsStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3LDATopicWordsStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def userLDAStatisticsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): UserLDAStatisticsStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3UserLDAStatisticsStore(bucketName, amazonS3Client, accessLog)
  }

}

case class LDAInfoStoreDevModule() extends ProdOrElseDevStoreModule(LDAInfoStoreProdModule()) with LDAInfoStoreModule with Logging {
  def configure() {}

  val tw1 = Array(Map("scala" -> 0.1f, "kifi" -> 0.2f), Map("food" -> 0.4f, "recipe" -> 0.005f))
  val tw2 = Array(Map("ode" -> 0.5f, "pde" -> 0.5f), Map("bayes" -> 0.8f, "regression" -> 0.2f))
  val topicwords = List(tw1, tw2)

  @Singleton
  @Provides
  def topicWordsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDATopicWordsStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.topicWordsStore(amazonS3Client, accessLog)
    ) getOrElse {
        val store = new InMemoryLDATopicWordsStore
        (ModelVersions.availableLDAVersions.take(2) zip topicwords) foreach { case (version, tw) => store.+=(MiscPrefix.LDA.topicWordsJsonFile, version, DenseLDATopicWords(tw)) }
        store
      }
  }

  @Singleton
  @Provides
  def userLDAStatisticsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): UserLDAStatisticsStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.userLDAStatisticsStore(amazonS3Client, accessLog)
    ) getOrElse {
        new InMemoryUserLDAStatisticsStore()
      }
  }

}
