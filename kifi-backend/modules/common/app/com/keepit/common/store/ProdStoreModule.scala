package com.keepit.common.store

import play.api.Play.current
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provider, Provides, Singleton }
import com.amazonaws.services.s3.{ AmazonS3Client, AmazonS3 }
import com.keepit.search._
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.common.logging.AccessLog

trait StoreModule extends ScalaModule {

}

abstract class ProdOrElseDevStoreModule[T <: StoreModule](val prodStoreModule: T) extends StoreModule {
  protected def whenConfigured[T](parameter: String)(expression: => T): Option[T] =
    current.configuration.getString(parameter).map(_ => expression)
}

trait ProdStoreModule extends StoreModule {

  @Singleton
  @Provides
  def amazonS3Client(basicAWSCredentials: BasicAWSCredentials): AmazonS3 = {
    new AmazonS3Client(basicAWSCredentials)
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
  def kifiInstallationStore(amazonS3Client: AmazonS3, accessLog: AccessLog): KifInstallationStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.install.bucket").get)
    new S3KifInstallationStoreImpl(bucketName, amazonS3Client, accessLog)
  }
}

abstract class DevStoreModule[T <: ProdStoreModule](override val prodStoreModule: T) extends ProdOrElseDevStoreModule(prodStoreModule) {

  @Singleton
  @Provides
  def amazonS3Client(awsCredentials: BasicAWSCredentials): AmazonS3 = {
    new AmazonS3Client(awsCredentials)
  }

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
  def kifInstallationStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): KifInstallationStore =
    whenConfigured("amazon.s3.install.bucket")(
      prodStoreModule.kifiInstallationStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryKifInstallationStoreImpl())
}
