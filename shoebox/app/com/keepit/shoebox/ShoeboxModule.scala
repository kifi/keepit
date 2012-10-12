package com.keepit.shoebox

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject._
import java.net.InetAddress
import akka.actor.ActorSystem
import akka.actor.Props
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckImpl
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.PostOfficeImpl
import com.keepit.common.net._
import com.keepit.scraper._
import com.keepit.inject._
import play.api.Play
import play.api.Play.current
import com.keepit.common.mail.MailSender
import com.keepit.search._
import com.amazonaws.services.s3._
import com.amazonaws.auth.BasicAWSCredentials

case class ShoeboxModule() extends ScalaModule {
  def configure(): Unit = {
    
    var appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
    
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def articleStore(bucketName: S3Bucket, amazonS3Client: AmazonS3): ArticleStore = 
    new S3ArticleStoreImpl(bucketName, amazonS3Client)
  
  @Singleton
  @Provides
  def s3Bucket: S3Bucket = S3Bucket(current.configuration.getString("amazon.s3.bucket").get)
  
  @Singleton
  @Provides
  def amazonS3Client(): AmazonS3 = { 
    var conf = current.configuration.getConfig("amazon.s3").get
    val awsCredentials = new BasicAWSCredentials(
        conf.getString("accessKey").get, 
        conf.getString("secretKey").get)
    new AmazonS3Client(awsCredentials)
  }
  
  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin("shoebox-actor-system")
  
  @Provides
  def httpClientProvider: HttpClient = new HttpClientImpl()
  
  @Provides
  @AppScoped
  def healthcheckProvider(system: ActorSystem, postOffice: PostOffice): Healthcheck = {
    val host = InetAddress.getLocalHost().getCanonicalHostName()
    new HealthcheckImpl(system, host, postOffice)
  }

  @Provides
  @AppScoped
  def mailSenderProvider(system: ActorSystem, healthcheck: Healthcheck, httpClient: HttpClient): MailSender = {
    val url = "https://api.postmarkapp.com/email"
    val postmarkToken = "61311d22-d1cc-400b-865e-ffb95027251f"
    new MailSender(system, url, postmarkToken, healthcheck, httpClient)
  }
}