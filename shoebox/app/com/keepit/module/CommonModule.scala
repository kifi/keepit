package com.keepit.module

import java.io.File
import java.net.InetAddress
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import com.keepit.common.zookeeper._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.actor._
import com.keepit.common.plugin._
import com.keepit.common.analytics._
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.healthcheck.{HealthcheckHost, HealthcheckPluginImpl, HealthcheckPlugin, HealthcheckActor}
import com.keepit.common.logging.Logging
import com.keepit.common.controller.FortyTwoCookies._
import com.keepit.common.mail.{MailSenderPluginImpl, MailSenderPlugin, PostOffice}
import com.keepit.common.net.HttpClient
import com.keepit.common.net.HttpClientImpl
import com.keepit.inject.{FortyTwoModule, AppScoped}
import com.keepit.scraper.ScraperConfig
import com.keepit.scraper.{HttpFetcherImpl, HttpFetcher}
import com.keepit.search._
import com.keepit.shoebox.{ShoeboxServiceClientImpl, ShoeboxServiceClient}
import com.mongodb.casbah.MongoConnection
import com.tzavellas.sse.guice.ScalaModule
import akka.actor.ActorSystem
import play.api.Play.current
import com.keepit.common.time.Clock
import com.google.inject.Provider
import play.api.Play
import play.api.Mode.Mode
import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxCacheProvider
import com.keepit.common.mail.LocalPostOffice
import com.keepit.shoebox.ClickHistoryTracker
import com.keepit.shoebox.BrowsingHistoryTracker
import com.google.inject.Provider

class CommonModule extends ScalaModule with Logging {

  def configure() {
    install(new FortyTwoModule)
    install(new MemcachedCacheModule)
    install(new S3Module)
    install(new DiscoveryModule)

    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[play.api.Application].toProvider(new Provider[play.api.Application] {
      def get(): play.api.Application = current
    }).in[AppScoped]
  }

  @Provides
  def globalSchedulingEnabled: SchedulingEnabled = SchedulingEnabled.LeaderOnly

  @Singleton
  @Provides
  def playMode: Mode = current.mode

  @Singleton
  @Provides
  def kifiInstallationCookie: KifiInstallationCookie = new KifiInstallationCookie(current.configuration.getString("session.domain"))

  @Singleton
  @Provides
  def impersonateCookie: ImpersonateCookie = new ImpersonateCookie(current.configuration.getString("session.domain"))

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("shoebox-actor-system",
        Play.current.configuration.underlying,
        Play.current.classloader))

  @Provides
  def httpClientProvider(healthcheckPlugin: HealthcheckPlugin): HttpClient = new HttpClientImpl(healthcheckPlugin = healthcheckPlugin)

  @Provides
  @AppScoped
  def healthcheckHost(): HealthcheckHost = HealthcheckHost(InetAddress.getLocalHost().getHostName())

  @Provides
  @AppScoped
  def healthcheckProvider(actorFactory: ActorFactory[HealthcheckActor],
      services: FortyTwoServices, host: HealthcheckHost, schedulingProperties: SchedulingProperties): HealthcheckPlugin = {
    new HealthcheckPluginImpl(actorFactory, services, host, schedulingProperties)
  }

  @Singleton
  @Provides
  def scraperConfig: ScraperConfig = ScraperConfig()

  @Singleton
  @Provides
  def httpFetcher: HttpFetcher = {
    new HttpFetcherImpl(
      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
      connectionTimeout = 30000,
      soTimeOut = 30000,
      trustBlindly = true
    )
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
