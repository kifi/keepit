package com.keepit.shoebox

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject._
import java.net.InetAddress
import akka.actor.ActorSystem
import akka.actor.Props
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.mail.MailSenderPlugin
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.PostOfficeImpl
import com.keepit.common.net._
import com.keepit.common.store.S3Bucket
import com.keepit.common.social._
import com.keepit.common.logging.Logging
import com.keepit.scraper._
import com.keepit.inject._
import com.keepit.search._
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.graph.URIGraphPluginImpl
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.search.index.ArticleIndexerPluginImpl
import play.api.Play
import play.api.Play.current
import com.amazonaws.services.s3._
import com.amazonaws.auth.BasicAWSCredentials
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import java.io.File
import com.keepit.common.mail.{MailSenderPlugin, MailSenderPluginImpl}
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.HealthcheckPluginImpl
import akka.actor.Scheduler

case class ShoeboxModule() extends ScalaModule with Logging {
  def configure(): Unit = {
    install(FortyTwoModule())
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]
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
  def socialUserRawInfoStore(amazonS3Client: AmazonS3): SocialUserRawInfoStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.social.bucket").get)
    new S3SocialUserRawInfoStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def amazonS3Client(): AmazonS3 = {
    val conf = current.configuration.getConfig("amazon.s3").get
    val awsCredentials = new BasicAWSCredentials(
        conf.getString("accessKey").get,
        conf.getString("secretKey").get)
    new AmazonS3Client(awsCredentials)
  }

  @Singleton
  @Provides
  def articleIndexer(articleStore: ArticleStore, uriGraph: URIGraph): ArticleIndexer = {
    val dirPath = current.configuration.getString("index.article.directory").get
    val dir = new File(dirPath).getCanonicalFile()
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new Exception("could not create dir %s".format(dir))
      }
    }
    log.info("storing search index in %s".format(dir.getAbsolutePath()))
    ArticleIndexer(new MMapDirectory(dir), articleStore)
  }

  @Singleton
  @Provides
  def uriGraph: URIGraph = {
    val dirPath = current.configuration.getString("index.urigraph.directory").get
    val dir = new File(dirPath).getCanonicalFile()
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new Exception("could not create dir %s".format(dir))
      }
    }
    log.info("storing URIGraph in %s".format(dir.getAbsolutePath()))
    URIGraph(new MMapDirectory(dir))
  }

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin("shoebox-actor-system")

  @Provides
  def httpClientProvider: HttpClient = new HttpClientImpl()

  @Provides
  @AppScoped
  def healthcheckProvider(system: ActorSystem, postOffice: PostOffice): HealthcheckPlugin = {
    val host = InetAddress.getLocalHost().getCanonicalHostName()
    new HealthcheckPluginImpl(system, host, postOffice)
  }

}
