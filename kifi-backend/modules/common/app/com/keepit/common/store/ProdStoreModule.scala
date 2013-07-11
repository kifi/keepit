package com.keepit.common.store

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provider, Provides, Singleton}
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3}
import com.keepit.search._
import play.api.Play._
import com.keepit.common.analytics._
import com.amazonaws.auth.BasicAWSCredentials
import com.mongodb.casbah.MongoConnection
import com.keepit.learning.topicmodel._

trait StoreModule extends ScalaModule {

  @Singleton
  @Provides
  def amazonS3Client(): AmazonS3 = {
    val conf = current.configuration.getConfig("amazon.s3").get
    val awsCredentials = new BasicAWSCredentials(
      conf.getString("accessKey").get,
      conf.getString("secretKey").get)
    new AmazonS3Client(awsCredentials)
  }

}

trait ProdStoreModule extends StoreModule {

  @Singleton
  @Provides
  def probablisticLRUStore(amazonS3Client: AmazonS3): ProbablisticLRUStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.flowerFilter.bucket").get)
    new S3ProbablisticLRUStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def articleSearchResultStore(amazonS3Client: AmazonS3): ArticleSearchResultStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.articleSearch.bucket").get)
    new S3ArticleSearchResultStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def articleStore(amazonS3Client: AmazonS3): ArticleStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.article.bucket").get)
    new S3ArticleStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def wordTopicStore(amazonS3Client: AmazonS3): WordTopicStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.wordTopic.bucket").get)
    new S3WordTopicStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def wordTopicBlobStore(amazonS3Client: AmazonS3): WordTopicBlobStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.wordTopic.bucket").get)
    new S3WordTopicBlobStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def wordStore(amazonS3Client: AmazonS3): WordStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.wordTopic.bucket").get)
    new S3WordStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def mongoEventStore(): MongoEventStore = {
    current.configuration.getString("mongo.events.server").map { server =>
      val mongoConn = MongoConnection(server)
      val mongoDB = mongoConn(current.configuration.getString("mongo.events.database").getOrElse("events"))
      new MongoS3EventStoreImpl(mongoDB)
    }.get
  }
}

abstract class DevStoreModule[T <: ProdStoreModule](val prodStoreModule: T) extends StoreModule {

  protected def whenConfigured[T](parameter: String)(expression: => T): Option[T] =
    current.configuration.getString(parameter).map(_ => expression)

  @Singleton
  @Provides
  def probablisticLRUStore(amazonS3ClientProvider: Provider[AmazonS3]): ProbablisticLRUStore =
    whenConfigured("amazon.s3.flowerFilter.bucket")(
      prodStoreModule.probablisticLRUStore(amazonS3ClientProvider.get)
    ).getOrElse(new InMemoryProbablisticLRUStoreImpl())

  @Singleton
  @Provides
  def articleSearchResultStore(amazonS3ClientProvider: Provider[AmazonS3]): ArticleSearchResultStore =
    whenConfigured("amazon.s3.articleSearch.bucket")(
      prodStoreModule.articleSearchResultStore(amazonS3ClientProvider.get)
    ).getOrElse(new InMemoryArticleSearchResultStoreImpl())

  @Singleton
  @Provides
  def articleStore(amazonS3ClientProvider: Provider[AmazonS3]): ArticleStore =
    whenConfigured("amazon.s3.article.bucket")(
      prodStoreModule.articleStore(amazonS3ClientProvider.get)
    ).getOrElse(new InMemoryArticleStoreImpl())

  @Singleton
  @Provides
  def wordTopicStore(amazonS3ClientProvider: Provider[AmazonS3]): WordTopicStore =
    whenConfigured("amazon.s3.wordTopic.bucket")(
      prodStoreModule.wordTopicStore(amazonS3ClientProvider.get)
    ).getOrElse(new InMemoryWordTopicStoreImpl())

  @Singleton
  @Provides
  def wordTopicBlobStore(amazonS3ClientProvider: Provider[AmazonS3]): WordTopicBlobStore =
    whenConfigured("amazon.s3.wordTopic.bucket")(
      prodStoreModule.wordTopicBlobStore(amazonS3ClientProvider.get)
    ).getOrElse(new InMemoryWordTopicBlobStoreImpl())

  @Singleton
  @Provides
  def wordStore(amazonS3ClientProvider: Provider[AmazonS3]): WordStore =
    whenConfigured("amazon.s3.wordTopic.bucket")(
      prodStoreModule.wordStore(amazonS3ClientProvider.get)
    ).getOrElse(new InMemoryWordStoreImpl())

  @Singleton
  @Provides
  def mongoEventStore(): MongoEventStore =
    whenConfigured("mongo.events.server")(
      prodStoreModule.mongoEventStore()
    ).getOrElse(new FakeMongoS3EventStore())
}
