package com.keepit.common.store

import play.api.Mode
import play.api.Mode._
import play.api.Play.current
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.time.Clock
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.{Provider, Provides, Singleton}
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3}
import com.keepit.search._
import com.keepit.common.analytics._
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.learning.topicmodel._
import com.keepit.common.logging.AccessLog

trait StoreModule extends ScalaModule {

}

trait ProdStoreModule extends StoreModule {

  @Singleton
  @Provides
  def amazonS3Client(): AmazonS3 = {
    val conf = current.configuration.getConfig("amazon").get
    val awsCredentials = new BasicAWSCredentials(
      conf.getString("accessKey").get,
      conf.getString("secretKey").get)
    new AmazonS3Client(awsCredentials)
  }

  @Singleton
  @Provides
  def probablisticLRUStore(amazonS3Client: AmazonS3, accessLog: AccessLog): ProbablisticLRUStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.flowerFilter.bucket").get)
    new S3ProbablisticLRUStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def articleSearchResultStore(amazonS3Client: AmazonS3, accessLog: AccessLog, initialSearchIdCache: InitialSearchIdCache, articleCache: ArticleSearchResultCache): ArticleSearchResultStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.articleSearch.bucket").get)
    new S3ArticleSearchResultStoreImpl(bucketName, amazonS3Client, accessLog, initialSearchIdCache, articleCache)
  }

  @Singleton
  @Provides
  def articleStore(amazonS3Client: AmazonS3, accessLog: AccessLog): ArticleStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.article.bucket").get)
    new S3ArticleStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def wordTopicStore(amazonS3Client: AmazonS3, accessLog: AccessLog): WordTopicStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.wordTopic.bucket").get)
    new S3WordTopicStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def wordTopicBlobStore(amazonS3Client: AmazonS3, accessLog: AccessLog): WordTopicBlobStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.wordTopic.bucket").get)
    new S3WordTopicBlobStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def wordStore(amazonS3Client: AmazonS3, accessLog: AccessLog): WordStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.wordTopic.bucket").get)
    new S3WordStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def topicWordsStore(amazonS3Client: AmazonS3, accessLog: AccessLog): TopicWordsStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.wordTopic.bucket").get)
    new S3TopicWordsStoreImpl(bucketName, amazonS3Client, accessLog)
  }
}

abstract class DevStoreModule[T <: ProdStoreModule](val prodStoreModule: T) extends StoreModule {

  @Singleton
  @Provides
  def amazonS3Client(): AmazonS3 = {
    val conf = current.configuration.getConfig("amazon").get
    val awsCredentials = new BasicAWSCredentials(
      conf.getString("accessKey").get,
      conf.getString("secretKey").get)
    new AmazonS3Client(awsCredentials)
  }

  protected def whenConfigured[T](parameter: String)(expression: => T): Option[T] =
    current.configuration.getString(parameter).map(_ => expression)

  @Singleton
  @Provides
  def probablisticLRUStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): ProbablisticLRUStore =
    whenConfigured("amazon.s3.flowerFilter.bucket")(
      prodStoreModule.probablisticLRUStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryProbablisticLRUStoreImpl())

  @Singleton
  @Provides
  def articleSearchResultStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog, initialSearchIdCache: InitialSearchIdCache, articleCache: ArticleSearchResultCache): ArticleSearchResultStore =
    whenConfigured("amazon.s3.articleSearch.bucket")(
      prodStoreModule.articleSearchResultStore(amazonS3ClientProvider.get, accessLog, initialSearchIdCache, articleCache)
    ).getOrElse(new InMemoryArticleSearchResultStoreImpl())

  @Singleton
  @Provides
  def articleStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): ArticleStore =
    whenConfigured("amazon.s3.article.bucket")(
      prodStoreModule.articleStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryArticleStoreImpl())

  @Singleton
  @Provides
  def wordTopicStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): WordTopicStore =
    whenConfigured("amazon.s3.wordTopic.bucket")(
      prodStoreModule.wordTopicStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryWordTopicStoreImpl())

  @Singleton
  @Provides
  def wordTopicBlobStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): WordTopicBlobStore =
    whenConfigured("amazon.s3.wordTopic.bucket")(
      prodStoreModule.wordTopicBlobStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryWordTopicBlobStoreImpl())

  @Singleton
  @Provides
  def wordStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): WordStore =
    whenConfigured("amazon.s3.wordTopic.bucket")(
      prodStoreModule.wordStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryWordStoreImpl())

  @Singleton
  @Provides
  def topicWordsStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): TopicWordsStore =
    whenConfigured("amazon.s3.wordTopic.bucket")(
      prodStoreModule.topicWordsStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryTopicWordsStoreImpl())
}
