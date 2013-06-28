package com.keepit.common.analytics

import net.codingwell.scalaguice.{ScalaMultibinder, ScalaModule}
import com.keepit.inject.AppScoped
import com.keepit.common.analytics.reports.{ReportBuilderPluginImpl, ReportBuilderPlugin}
import com.google.inject.{Provider, Provides, Singleton}
import com.keepit.common.db.slick.Database
import com.keepit.model.{NormalizedURIRepo, UserRepo}
import com.keepit.search.SearchServiceClient
import com.keepit.common.time.Clock
import com.keepit.common.service.FortyTwoServices
import play.api.Play._

trait AnalyticsModule extends ScalaModule

case class AnalyticsImplModule() extends AnalyticsModule {

  def configure() {
    bind[EventPersister].to[EventPersisterImpl].in[AppScoped]
    val listenerBinder = ScalaMultibinder.newSetBinder[EventListener](binder)
    listenerBinder.addBinding.to[ResultClickedListener]
    listenerBinder.addBinding.to[UsefulPageListener]
    listenerBinder.addBinding.to[SliderShownListener]
    listenerBinder.addBinding.to[SearchUnloadListener]
    bind[ReportBuilderPlugin].to[ReportBuilderPluginImpl].in[AppScoped]
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
    fortyTwoServices: FortyTwoServices
  ): SearchUnloadListener = {
    new SearchUnloadListenerImpl(db, userRepo, normalizedURIRepo, persistEventProvider, store,
      searchClient, clock, fortyTwoServices)
  }
}

case class DevAnalyticsModule() extends AnalyticsModule {

  def configure() {
    bind[EventPersister].to[FakeEventPersisterImpl].in[AppScoped]
    ScalaMultibinder.newSetBinder[EventListener](binder)
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
    current.configuration.getBoolean("event-listener.searchUnload").getOrElse(false) match {
      case true =>  new SearchUnloadListenerImpl(db,userRepo, normalizedURIRepo, persistEventProvider, store, searchClient, clock, fortyTwoServices)
      case false => new FakeSearchUnloadListenerImpl(userRepo, normalizedURIRepo)
    }
  }
}

