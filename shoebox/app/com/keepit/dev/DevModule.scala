package com.keepit.dev

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject.Provides
import com.google.inject.Provider
import akka.actor.ActorSystem
import akka.actor.Props
import com.keepit.model.NormalizedURI
import com.keepit.search.Article
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
import com.keepit.common.db.Id
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
import com.keepit.common.social.{SocialGraphPluginImpl, SocialGraphPlugin}

case class DevModule() extends ScalaModule with Logging {
  def configure(): Unit = {
    var appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
    
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def articleStore: ArticleStore = {
    var conf = current.configuration.getConfig("amazon.s3").get
    conf.getString("bucket") match {
      case None => 
        new HashMap[Id[NormalizedURI], Article] with ArticleStore
      case Some(bucketName) =>
        val bucket = S3Bucket(bucketName)
        val awsCredentials = new BasicAWSCredentials(
            conf.getString("accessKey").get, 
            conf.getString("secretKey").get)
        val client = new AmazonS3Client(awsCredentials)
        new S3ArticleStoreImpl(bucket, client)
    }
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
  
  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin("shoebox-dev-actor-system")
}
