package com.keepit.cortex.nlp

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.logging.AccessLog
import com.keepit.common.store._
import net.codingwell.scalaguice.ScalaModule
import play.api.libs.json._
import play.api.Play._
import com.keepit.cortex._

case class Stopwords(words: Set[String]) {
  def contains(word: String): Boolean = words.contains(word)
}

object Stopwords {
  implicit val format = Json.format[Stopwords]
}

trait StopwordsStore extends ObjectStore[String, Stopwords]

class S3StopwordsStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val formatter: Format[Stopwords] = Stopwords.format)
    extends S3JsonStore[String, Stopwords] with StopwordsStore {
  val prefix = MiscPrefix.Stopwords.stopwordsFoler
  override def keyPrefix() = prefix
}

class InMemoryStopwordsStore extends InMemoryObjectStore[String, Stopwords] with StopwordsStore

trait StopwordsStoreModule extends StoreModule

case class StopwordsProdStoreModule() extends StopwordsStoreModule {
  def configure {}

  @Singleton
  @Provides
  def stopwordsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): StopwordsStore = {
    val bucketName = S3Bucket(current.configuration.getString(S3_CORTEX_BUCKET).get)
    new S3StopwordsStore(bucketName, amazonS3Client, accessLog)
  }
}

case class StopwordsDevStoreModule() extends ProdOrElseDevStoreModule(StopwordsProdStoreModule()) with StopwordsStoreModule {
  def configure {}

  @Singleton
  @Provides
  def stopwordsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): StopwordsStore = {
    whenConfigured(S3_CORTEX_BUCKET) {
      prodStoreModule.stopwordsStore(amazonS3Client, accessLog)
    } getOrElse {
      val store = new InMemoryStopwordsStore
      store += (MiscPrefix.Stopwords.stopwordsJsonFile, Stopwords(Set()))
      store
    }
  }
}

case class StopwordsModule(store: StopwordsStoreModule) extends ScalaModule {

  def configure { install(store) }
  @Singleton
  @Provides
  def stopwords(store: StopwordsStore): Stopwords = store.syncGet(MiscPrefix.Stopwords.stopwordsJsonFile).get

}
