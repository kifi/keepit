package com.keepit.cortex.models.lda

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{Provides, Singleton}
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.S3Bucket
import com.keepit.cortex._
import play.api.Play.current
import com.keepit.common.store.ProdOrElseDevStoreModule
import com.keepit.common.store.StoreModule
import com.keepit.common.logging.Logging


trait LDAInfoStoreModule extends StoreModule

case class LDAInfoStoreProdModule() extends LDAInfoStoreModule with Logging{
  def configure(){}

  val TOPIC_WORDS_FILE = "topic_words"

  @Singleton
  @Provides
  def topicWordsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDATopicWordsStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3LDATopicWordsStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def topicWords(store: LDATopicWordsStore): DenseLDATopicWords = {
    log.info("loading lda topic words")
    val version = ModelVersions.denseLDAVersion
    store.get(TOPIC_WORDS_FILE, version).get
  }

}

case class LDAInfoStoreDevModule() extends ProdOrElseDevStoreModule(LDAInfoStoreProdModule()) with LDAInfoStoreModule with Logging{
  def configure(){}

  val TOPIC_WORDS_FILE = "topic_words"

  @Singleton
  @Provides
  def topicWordsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LDATopicWordsStore = {
    whenConfigured(S3_CORTEX_BUCKET)(
      prodStoreModule.topicWordsStore(amazonS3Client, accessLog)
    ) getOrElse {
      val topicWords = Array(Map("a" -> 0.1f, "b" -> 0.2f), Map("c" -> 0.4f, "d" -> 0.2f, "e" -> 0.005f))
      val version = ModelVersions.denseLDAVersion
      val store = new InMemoryLDATopicWordsStore
      store.+=(TOPIC_WORDS_FILE, version, DenseLDATopicWords(topicWords))
      store
    }
  }

  @Singleton
  @Provides
  def topicWords(store: LDATopicWordsStore): DenseLDATopicWords = {
    log.info("loading lda topic words")
    val version = ModelVersions.denseLDAVersion
    store.get(TOPIC_WORDS_FILE, version).get
  }
}
