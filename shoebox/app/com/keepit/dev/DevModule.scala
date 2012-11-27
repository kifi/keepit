package com.keepit.dev

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject.Provides
import com.google.inject.Provider
import akka.actor.ActorSystem
import akka.actor.Props
import com.keepit.model.{NormalizedURI, SocialUserInfo}
import com.keepit.search.Article
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.graph.URIGraphPluginImpl
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.search.index.ArticleIndexerPluginImpl
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckImpl
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.PostOfficeImpl
import com.keepit.common.net.HttpClient
import com.keepit.common.net.HttpClientImpl
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.inject._
import com.keepit.scraper._
import com.keepit.common.logging.Logging
import com.keepit.search.ArticleStore
import scala.collection.mutable.HashMap
import com.keepit.search._
import com.amazonaws.services.s3._
import com.amazonaws.auth.BasicAWSCredentials
import play.api.Play.current
import com.google.inject.Singleton
import org.apache.lucene.store.Directory
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.store.MMapDirectory
import java.io.File
import com.keepit.common.store.S3Bucket
import com.keepit.common.social._

case class DevModule() extends ScalaModule with Logging {
  def configure(): Unit = {
    install(FortyTwoModule())
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
    bind[SocialTokenRefresher].to[SocialTokenRefresherImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def amazonS3Client(): AmazonS3 = {
    val conf = current.configuration.getConfig("amazon.s3").get
    val awsCredentials = new BasicAWSCredentials(conf.getString("accessKey").get, conf.getString("secretKey").get)
    println("using awsCredentials: %s -> %s".format(awsCredentials.getAWSAccessKeyId(), awsCredentials.getAWSSecretKey()))
    new AmazonS3Client(awsCredentials)
  }

  @Singleton
  @Provides
  def articleSearchResultStore(client: AmazonS3): ArticleSearchResultStore =
    current.configuration.getString("amazon.s3.articleSearch.bucket") match {
      case None => new HashMap[ExternalId[ArticleSearchResultRef], ArticleSearchResult] with ArticleSearchResultStore
      case Some(bucketName) => new S3ArticleSearchResultStoreImpl(S3Bucket(bucketName), client)
    }

  @Singleton
  @Provides
  def articleStore(client: AmazonS3): ArticleStore =
    current.configuration.getString("amazon.s3.article.bucket") match {
      case None => new HashMap[Id[NormalizedURI], Article] with ArticleStore
      case Some(bucketName) => new S3ArticleStoreImpl(S3Bucket(bucketName), client)
    }


  @Singleton
  @Provides
  def socialUserRawInfoStore(client: AmazonS3): SocialUserRawInfoStore =
    current.configuration.getString("amazon.s3.social.bucket") match {
      case None => new HashMap[Id[SocialUserInfo], SocialUserRawInfo] with SocialUserRawInfoStore
      case Some(bucketName) => new S3SocialUserRawInfoStoreImpl(S3Bucket(bucketName), client)
    }

  @Singleton
  @Provides
  def articleIndexer(articleStore: ArticleStore): ArticleIndexer = {
    val indexDir = current.configuration.getString("index.article.directory") match {
      case None =>
        new RAMDirectory()
      case Some(dirPath) =>
        val dir = new File(dirPath).getCanonicalFile()
        if (!dir.exists()) {
          if (!dir.mkdirs()) {
            throw new Exception("could not create dir %s".format(dir))
          }
        }
        new MMapDirectory(dir)
    }
    ArticleIndexer(indexDir, articleStore)
  }

  @Provides
  def httpClientProvider: HttpClient = new HttpClientImpl()

  @Singleton
  @Provides
  def uriGraph: URIGraph = {
    val indexDir = current.configuration.getString("index.urigraph.directory") match {
      case None =>
        new RAMDirectory()
      case Some(dirPath) =>
        val dir = new File(dirPath).getCanonicalFile()
        if (!dir.exists()) {
          if (!dir.mkdirs()) {
            throw new Exception("could not create dir %s".format(dir))
          }
        }
        new MMapDirectory(dir)
    }
    URIGraph(indexDir)
  }

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin("shoebox-dev-actor-system")
}
