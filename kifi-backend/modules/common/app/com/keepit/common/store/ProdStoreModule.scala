package com.keepit.common.store

import java.io.File

import com.amazonaws.services.s3.transfer.TransferManager
import com.keepit.rover.sensitivity.{ PornWordLikelihood, InMemoryPornWordLikelihoodStore, S3PornWordLikelihoodStore, PornWordLikelihoodStore }
import com.keepit.search.tracking.{ InMemoryProbablisticLRUStoreImpl, S3ProbablisticLRUStoreImpl, ProbablisticLRUStore }
import org.apache.commons.io.FileUtils
import play.api.Play.current
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provider, Provides, Singleton }
import com.amazonaws.services.s3.{ AmazonS3Client, AmazonS3 }
import com.keepit.search._
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.common.logging.AccessLog

import scala.concurrent.ExecutionContext

trait StoreModule extends ScalaModule

abstract class ProdOrElseDevStoreModule[T <: StoreModule](val prodStoreModule: T) extends StoreModule {
  protected def whenConfigured[T](parameter: String)(expression: => T): Option[T] =
    play.api.Play.maybeApplication.map(_.configuration.getString(parameter).map(_ => expression)).flatten
}

trait ProdStoreModule extends StoreModule {

  protected def forceMakeTemporaryDirectory(parent: String, child: String): File = {
    val temp = new File(parent, child).getCanonicalFile
    FileUtils.deleteDirectory(temp)
    FileUtils.forceMkdir(temp)
    temp.deleteOnExit()
    temp
  }

  @Singleton
  @Provides
  def amazonS3Client(basicAWSCredentials: BasicAWSCredentials): AmazonS3 = {
    new AmazonS3Client(basicAWSCredentials)
  }

  @Singleton
  @Provides
  def transferManager(s3client: AmazonS3): TransferManager = {
    new TransferManager(s3client)
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

  @Provides @Singleton
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

  @Provides @Singleton
  def bayesPornDetectorStore(amazonS3Client: AmazonS3, accessLog: AccessLog): PornWordLikelihoodStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.bayes.porn.detector.bucket").get)
    new S3PornWordLikelihoodStore(bucketName, amazonS3Client, accessLog)
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
  def transferManager(s3client: AmazonS3): TransferManager = {
    new TransferManager(s3client)
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

  @Provides @Singleton
  def articleStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): ArticleStore =
    whenConfigured("amazon.s3.article.bucket")(
      prodStoreModule.articleStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryArticleStoreImpl())

  @Singleton
  @Provides
  def kifInstallationStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): KifiInstallationStore =
    whenConfigured("amazon.s3.install.bucket")(
      prodStoreModule.kifiInstallationStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryKifiInstallationStoreImpl())

  @Provides @Singleton
  def bayesPornDetectorStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog) = {
    whenConfigured("amazon.s3.bayes.porn.detector.bucket")(
      prodStoreModule.bayesPornDetectorStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryPornWordLikelihoodStore() {
        override def syncGet(key: String) = Some(PornWordLikelihood(Map("a" -> 1f)))
      })
  }
}
