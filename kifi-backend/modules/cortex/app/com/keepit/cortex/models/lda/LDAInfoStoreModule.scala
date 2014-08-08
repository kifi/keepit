package com.keepit.cortex.models.lda

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.S3Bucket
import com.keepit.cortex._
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
  def topicConfigsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAConfigStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3LDAConfigStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def topicWords(store: LDATopicWordsStore): DenseLDATopicWords = {
    log.info("loading lda topic words")
    val version = ModelVersions.denseLDAVersion
    store.get(MiscPrefix.LDA.topicWordsJsonFile, version).get
  }

  @Singleton
  @Provides
  def topicConfigs(store: LDAConfigStore, rep: LDAWordRepresenter): LDATopicConfigurations = {
    log.info("loading lda topic configs")
    val version = ModelVersions.denseLDAVersion
    store.get(MiscPrefix.LDA.topicConfigsJsonFile, version) match {
      case Some(configs) => configs
      case None => {
        val conf = generateDefaultConfig(rep.lda.dimension)
        store.+=(MiscPrefix.LDA.topicConfigsJsonFile, version, conf)
        conf
      }
    }
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

  @Singleton
  @Provides
  def topicWordsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDATopicWordsStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.topicWordsStore(amazonS3Client, accessLog)
    ) getOrElse {
        val topicWords = Array(Map("scala" -> 0.1f, "kifi" -> 0.2f), Map("food" -> 0.4f, "recipe" -> 0.005f))
        val version = ModelVersions.denseLDAVersion
        val store = new InMemoryLDATopicWordsStore
        store.+=(MiscPrefix.LDA.topicWordsJsonFile, version, DenseLDATopicWords(topicWords))
        store
      }
  }

  @Singleton
  @Provides
  def topicConfigsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDAConfigStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.topicConfigsStore(amazonS3Client, accessLog)
    ) getOrElse {
        val version = ModelVersions.denseLDAVersion
        val store = new InMemoryLDAConfigStore
        val config = Map("0" -> LDATopicConfiguration("foo", false, false), "1" -> LDATopicConfiguration("bar", false, false))
        store.+=(MiscPrefix.LDA.topicConfigsJsonFile, version, LDATopicConfigurations(config))
      }
  }

  @Singleton
  @Provides
  def topicWords(store: LDATopicWordsStore): DenseLDATopicWords = {
    log.info("loading lda topic words")
    val version = ModelVersions.denseLDAVersion
    store.get(MiscPrefix.LDA.topicWordsJsonFile, version).get
  }

  @Singleton
  @Provides
  def topicConfigs(store: LDAConfigStore, rep: LDAWordRepresenter): LDATopicConfigurations = {
    log.info("loading lda topic configs")
    val version = ModelVersions.denseLDAVersion
    store.get(MiscPrefix.LDA.topicConfigsJsonFile, version) match {
      case Some(config) => config
      case None => {
        val conf = generateDefaultConfig(rep.lda.dimension)
        store.+=(MiscPrefix.LDA.topicConfigsJsonFile, version, conf)
        conf
      }
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
