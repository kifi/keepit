package com.keepit.shoebox

import com.google.common.io.Files
import com.google.inject.{Provides, Singleton}
import com.keepit.classify.DomainTagImportSettings
import com.keepit.common.analytics.reports._
import com.keepit.common.crypto._
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.social._
import com.keepit.common.store.{ImageDataIntegrityPluginImpl, ImageDataIntegrityPlugin}
import com.keepit.inject.AppScoped
import com.keepit.realtime._
import com.keepit.scraper._
import com.tzavellas.sse.guice.ScalaModule
import play.api.Play.current
import com.google.inject.multibindings.Multibinder
import com.keepit.common.analytics._
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.google.inject.Provider
import com.keepit.search.SearchServiceClient
import com.keepit.common.time.Clock
import com.keepit.common.service.FortyTwoServices

class ShoeboxModule() extends ScalaModule with Logging {
  def configure() {
    println("configuring ShoeboxModule")
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

    bind[PersistEventPlugin].to[PersistEventPluginImpl].in[AppScoped]
    val listenerBinder = Multibinder.newSetBinder(binder(), classOf[EventListenerPlugin])
    listenerBinder.addBinding().to(classOf[ResultClickedListener])
    listenerBinder.addBinding().to(classOf[UsefulPageListener])
    listenerBinder.addBinding().to(classOf[SliderShownListener])
    listenerBinder.addBinding().to(classOf[SearchUnloadListener])
  }

  @Singleton
  @Provides
  def searchUnloadProvider(
    db: Database,
    userRepo: UserRepo,
    normalizedURIRepo: NormalizedURIRepo,
    persistEventProvider: Provider[PersistEventPlugin],
    store: MongoEventStore,
    searchClient: SearchServiceClient,
    clock: Clock,
    fortyTwoServices: FortyTwoServices): SearchUnloadListener = {
    new SearchUnloadListenerImpl(db, userRepo, normalizedURIRepo, persistEventProvider, store, searchClient, clock, fortyTwoServices)
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

}
