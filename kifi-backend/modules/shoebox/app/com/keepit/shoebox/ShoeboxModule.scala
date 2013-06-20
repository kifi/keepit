package com.keepit.shoebox

import scala.slick.session.{ Database => SlickDatabase }
import net.codingwell.scalaguice.{ScalaMultibinder, ScalaModule}
import com.google.common.io.Files
import com.google.inject.Provider
import com.google.inject.{ Provides, Singleton }
import com.keepit.classify.DomainTagImportSettings
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports._
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.ShoeboxActionAuthenticator
import com.keepit.common.crypto._
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckMailSender
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.LocalHealthcheckMailSender
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.net.HttpClient
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social._
import com.keepit.common.store.S3ImageStore
import com.keepit.common.store.{ ImageDataIntegrityPluginImpl, ImageDataIntegrityPlugin }
import com.keepit.common.time.Clock
import com.keepit.inject.AppScoped
import com.keepit.model._
import com.keepit.realtime._
import com.keepit.scraper._
import com.keepit.search.SearchServiceClient
import com.keepit.search.SearchServiceClientImpl
import com.keepit.social.SecureSocialAuthenticatorPlugin
import com.keepit.social.SecureSocialUserPlugin
import com.keepit.social.ShoeboxSecureSocialAuthenticatorPlugin
import com.keepit.social.ShoeboxSecureSocialUserPlugin
import play.api.Play
import play.api.Play.current
import play.api.db.DB
import com.keepit.common.cache.ShoeboxCacheModule
import securesocial.controllers.TemplatesPlugin
import com.keepit.learning.topicmodel.{WordTopicModel, LdaWordTopicModel, TopicUpdaterPlugin, TopicUpdaterPluginImpl}


class ShoeboxModule() extends ScalaModule with Logging {
  def configure() {
    install(new ShoeboxCacheModule)
    install(new SlickModule(new DbInfo() {
      //later on we can customize it by the application name
      lazy val database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
      lazy val driverName = Play.current.configuration.getString("db.shoebox.driver").get
      println("loading database driver %s".format(driverName))
    }))
    println("configuring ShoeboxModule")
    bind[ActionAuthenticator].to[ShoeboxActionAuthenticator]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]
    bind[SocialGraphRefresher].to[SocialGraphRefresherImpl].in[AppScoped]
    bind[ReportBuilderPlugin].to[ReportBuilderPluginImpl].in[AppScoped]
    bind[DataIntegrityPlugin].to[DataIntegrityPluginImpl].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
    bind[MailToKeepPlugin].to[MailToKeepPluginImpl].in[AppScoped]
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
    bind[UserEmailNotifierPlugin].to[UserEmailNotifierPluginImpl].in[AppScoped]
    bind[ConnectionUpdater].to[UserConnectionCreator]
    bind[ImageDataIntegrityPlugin].to[ImageDataIntegrityPluginImpl].in[AppScoped]
    bind[InvitationMailPlugin].to[InvitationMailPluginImpl].in[AppScoped]
    bind[NotificationConsistencyChecker].to[NotificationConsistencyCheckerImpl].in[AppScoped]
    bind[ChannelPlugin].to[ChannelPluginImpl].in[AppScoped]
    bind[TopicUpdaterPlugin].to[TopicUpdaterPluginImpl].in[AppScoped]

    bind[LocalPostOffice].to[ShoeboxPostOfficeImpl]
    bind[HealthcheckMailSender].to[LocalHealthcheckMailSender]
    bind[EventPersister].to[EventPersisterImpl].in[AppScoped]
    val listenerBinder = ScalaMultibinder.newSetBinder[EventListener](binder)
    listenerBinder.addBinding.to[ResultClickedListener]
    listenerBinder.addBinding.to[UsefulPageListener]
    listenerBinder.addBinding.to[SliderShownListener]
    listenerBinder.addBinding.to[SearchUnloadListener]

    val socialGraphBinder = ScalaMultibinder.newSetBinder[SocialGraph](binder)
    socialGraphBinder.addBinding.to[FacebookSocialGraph]
    socialGraphBinder.addBinding.to[LinkedInSocialGraph]
  }

  @Provides
  @Singleton
  def secureSocialTemplatesPlugin(app: play.api.Application): TemplatesPlugin = {
    new ShoeboxTemplatesPlugin(app)
  }

  @Singleton
  @Provides
  def secureSocialAuthenticatorPlugin(db: Database,
      suiRepo: SocialUserInfoRepo,
      usRepo: UserSessionRepo,
      healthPlugin: HealthcheckPlugin,
      app: play.api.Application): SecureSocialAuthenticatorPlugin = {
    new ShoeboxSecureSocialAuthenticatorPlugin(db, suiRepo, usRepo, healthPlugin, app)
  }

  @Singleton
  @Provides
  def secureSocialUserPlugin(db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    imageStore: S3ImageStore,
    healthcheckPlugin: HealthcheckPlugin,
    userExperimentRepo: UserExperimentRepo,
    emailRepo: EmailAddressRepo,
    socialGraphPlugin: SocialGraphPlugin): SecureSocialUserPlugin = {
    new ShoeboxSecureSocialUserPlugin(
      db, socialUserInfoRepo, userRepo, imageStore, healthcheckPlugin, userExperimentRepo, emailRepo, socialGraphPlugin)
  }

  @Singleton
  @Provides
  def searchUnloadProvider(
    db: Database,
    userRepo: UserRepo,
    normalizedURIRepo: NormalizedURIRepo,
    persistEventProvider: Provider[EventPersister],
    store: MongoEventStore,
    searchClient: SearchServiceClient,
    clock: Clock,
    fortyTwoServices: FortyTwoServices): SearchUnloadListener = {
    new SearchUnloadListenerImpl(db, userRepo, normalizedURIRepo, persistEventProvider, store,
        searchClient, clock, fortyTwoServices)
  }

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    val dirPath = Files.createTempDir().getAbsolutePath
    DomainTagImportSettings(localDir = dirPath, url = "http://www.komodia.com/clients/42.zip")
  }

  @Singleton
  @Provides
  def mailToKeepServerSettings: MailToKeepServerSettings = {
    val username = current.configuration.getString("mailtokeep.username").get
    val password = current.configuration.getString("mailtokeep.password").get
    val server = current.configuration.getString("mailtokeep.server").getOrElse("imap.gmail.com")
    val protocol = current.configuration.getString("mailtokeep.protocol").getOrElse("imaps")
    MailToKeepServerSettings(username = username, password = password, server = server, protocol = protocol)
  }

  @Singleton
  @Provides
  def userVoiceSSOTokenGenerator: UserVoiceTokenGenerator = {
    current.configuration.getString("userVoiceSSOToken") match {
      case Some(sso) =>
        new UserVoiceTokenGenerator {
          def createSSOToken(userId: String, displayName: String, email: String, avatarUrl: String): UserVoiceSSOToken =
            UserVoiceSSOToken(sso)
        }
      case None => new UserVoiceTokenGeneratorImpl()
    }
  }


  @Singleton
  @Provides
  def clickHistoryTracker(repo: ClickHistoryRepo, db: Database): ClickHistoryTracker = {
    val conf = current.configuration.getConfig("click-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new ClickHistoryTracker(filterSize, numHashFuncs, minHits, repo, db)
  }

  @Singleton
  @Provides
  def browsingHistoryTracker(browsingHistoryRepo: BrowsingHistoryRepo, db: Database): BrowsingHistoryTracker = {
    val conf = current.configuration.getConfig("browsing-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new BrowsingHistoryTracker(filterSize, numHashFuncs, minHits, browsingHistoryRepo, db)
  }

  @Singleton
  @Provides
  def sliderHistoryTracker(sliderHistoryRepo: SliderHistoryRepo, db: Database): SliderHistoryTracker = {
    val conf = current.configuration.getConfig("slider-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new SliderHistoryTracker(sliderHistoryRepo, db, filterSize, numHashFuncs, minHits)
  }

  @Singleton
  @Provides
  def searchServiceClient(client: HttpClient): SearchServiceClient = {
    new SearchServiceClientImpl(
      current.configuration.getString("service.search.host").get,
      current.configuration.getInt("service.search.port").get,
      client)
  }

  @Provides
  @Singleton
  def wordTopicModel: WordTopicModel = new LdaWordTopicModel

}
