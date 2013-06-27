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

trait AnalyticsModule extends ScalaModule

case class AnalyticsImplModule() extends AnalyticsModule {

  def configure {
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
                            fortyTwoServices: FortyTwoServices): SearchUnloadListener = {
    new SearchUnloadListenerImpl(db, userRepo, normalizedURIRepo, persistEventProvider, store,
      searchClient, clock, fortyTwoServices)
  }

}
