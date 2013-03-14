package com.keepit.shoebox

import java.net.InetAddress

import com.google.common.io.Files
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import com.keepit.classify.DomainTagImportSettings
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports._
import com.keepit.common.analytics.{UsefulPageListener, KifiResultClickedListener, EventListenerPlugin}
import com.keepit.common.cache._
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.HealthcheckPluginImpl
import com.keepit.common.logging.Logging
import com.keepit.common.mail.MailSenderPlugin
import com.keepit.common.mail.MailSenderPluginImpl
import com.keepit.common.mail.PostOffice
import com.keepit.common.net.HttpClient
import com.keepit.common.net.HttpClientImpl
import com.keepit.common.social.{SocialGraphRefresherImpl, SocialGraphRefresher, SocialGraphPlugin, SocialGraphPluginImpl}
import com.keepit.inject.AppScoped
import com.keepit.inject.FortyTwoModule
import com.keepit.model.SliderHistoryTracker
import com.keepit.module.{S3Module, SearchCommonModule}
import com.keepit.scraper._
import com.mongodb.casbah.MongoConnection
import com.tzavellas.sse.guice.ScalaModule

import akka.actor.ActorSystem
import play.api.Play.current

class ShoeboxModule() extends ScalaModule with Logging {
  def configure(): Unit = {
    println("configuring ShoeboxModule")
    install(new FortyTwoModule)
    install(new SearchCommonModule)
    install(new S3Module)
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
    bind[SocialGraphRefresher].to[SocialGraphRefresherImpl].in[AppScoped]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]
    bind[ReportBuilderPlugin].to[ReportBuilderPluginImpl].in[AppScoped]
    bind[DataIntegrityPlugin].to[DataIntegrityPluginImpl].in[AppScoped]

    bind[PersistEventPlugin].to[PersistEventPluginImpl].in[AppScoped]
    install(new MemcachedCacheModule)

    val listenerBinder = Multibinder.newSetBinder(binder(), classOf[EventListenerPlugin])
    listenerBinder.addBinding().to(classOf[KifiResultClickedListener])
    listenerBinder.addBinding().to(classOf[UsefulPageListener])
    listenerBinder.addBinding().to(classOf[SliderShownListener])
  }

  @Singleton
  @Provides
  def mongoEventStore(): MongoEventStore = {
    current.configuration.getString("mongo.events.server").map { server =>
      val mongoConn = MongoConnection(server)
      val mongoDB = mongoConn(current.configuration.getString("mongo.events.database").getOrElse("events"))
      new MongoEventStoreImpl(mongoDB)
    }.get
  }

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin("shoebox-actor-system")

  @Provides
  def httpClientProvider: HttpClient = new HttpClientImpl()

  @Provides
  @AppScoped
  def healthcheckProvider(system: ActorSystem, postOffice: PostOffice, services: FortyTwoServices): HealthcheckPlugin = {
    val host = InetAddress.getLocalHost().getCanonicalHostName()
    new HealthcheckPluginImpl(system, host, postOffice, services)
  }

  @Singleton
  @Provides
  def sliderHistoryTracker: SliderHistoryTracker = {
    val conf = current.configuration.getConfig("slider-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    SliderHistoryTracker(filterSize, numHashFuncs, minHits)
  }

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    val dirPath = Files.createTempDir().getAbsolutePath
    DomainTagImportSettings(localDir = dirPath, url = "http://www.komodia.com/clients/42.zip")
  }
}
