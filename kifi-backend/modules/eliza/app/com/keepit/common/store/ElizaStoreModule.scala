package com.keepit.common.store

import com.google.inject.{ Provider, Singleton, Provides }
import com.amazonaws.services.s3.{ AmazonS3Client, AmazonS3 }
import com.keepit.common.logging.AccessLog
import play.api.Play.current
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.search.ArticleStore
import com.keepit.search.S3ArticleStoreImpl
import com.keepit.search.InMemoryArticleStoreImpl

case class ElizaProdStoreModule() extends StoreModule {
  def configure() {

  }

  @Singleton
  @Provides
  def amazonS3Client(basicAWSCredentials: BasicAWSCredentials): AmazonS3 = {
    new AmazonS3Client(basicAWSCredentials)
  }

  @Singleton
  @Provides
  def articleStore(amazonS3Client: AmazonS3, accessLog: AccessLog): ArticleStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.article.bucket").get)
    new S3ArticleStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def kifiInstallationStore(amazonS3Client: AmazonS3, accessLog: AccessLog): KifiInstallationStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.install.bucket").get)
    new S3KifiInstallationStoreImpl(bucketName, amazonS3Client, accessLog)
  }
}

case class ElizaDevStoreModule() extends StoreModule {
  def configure() {

  }
  protected def whenConfigured[T](parameter: String)(expression: => T): Option[T] =
    play.api.Play.maybeApplication.map(_.configuration.getString(parameter).map(_ => expression)).flatten

  @Singleton
  @Provides
  def amazonS3Client(basicAWSCredentials: BasicAWSCredentials): AmazonS3 = {
    new AmazonS3Client(basicAWSCredentials)
  }

  @Singleton
  @Provides
  def articleStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): ArticleStore =
    whenConfigured("amazon.s3.article.bucket") {
      val bucketName = S3Bucket(current.configuration.getString("amazon.s3.install.bucket").get)
      new S3ArticleStoreImpl(bucketName, amazonS3ClientProvider.get, accessLog)
    }.getOrElse(new InMemoryArticleStoreImpl())

  @Singleton
  @Provides
  def kifInstallationStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): KifiInstallationStore =
    whenConfigured("amazon.s3.install.bucket") {
      val bucketName = S3Bucket(current.configuration.getString("amazon.s3.install.bucket").get)
      new S3KifiInstallationStoreImpl(bucketName, amazonS3ClientProvider.get, accessLog)
    }.getOrElse(new InMemoryKifiInstallationStoreImpl())
}
