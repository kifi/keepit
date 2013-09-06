package com.keepit.shoebox

import com.keepit.reports._
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.social.{SocialGraphModule, SecureSocialModule}
import com.keepit.common.mail.DevMailModule
import com.keepit.common.analytics.AnalyticsModule
import com.keepit.learning.topicmodel.TopicModelModule
import com.keepit.model.SliderHistoryTrackerModule
import com.keepit.scraper.ScraperModule
import com.keepit.realtime.WebSocketModule
import com.keepit.classify.DomainTagImporterModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.common.db.slick.SlickModule
import com.keepit.integrity.DataIntegrityModule

abstract class ShoeboxModule(
  val secureSocialModule: SecureSocialModule,
  val mailModule: DevMailModule,
  val storeModule: ShoeboxDevStoreModule,

  // Shoebox Functional Modules
  val slickModule: SlickModule,
  val scraperModule: ScraperModule,
  val socialGraphModule: SocialGraphModule,
  val analyticsModule: AnalyticsModule,
  val webSocketModule: WebSocketModule,
  val topicModelModule: TopicModelModule,
  val domainTagImporterModule: DomainTagImporterModule,
  val sliderHistoryTrackerModule: SliderHistoryTrackerModule,
  val userIndexModule: UserIndexModule,
  val geckoboardModule: GeckoboardModule,
  val dataIntegrityModule: DataIntegrityModule,
  val cacheModule: ShoeboxCacheModule,
  val clickHistoryModule: ShoeboxClickHistoryModule,
  val browsingHistoryModule: ShoeboxBrowsingHistoryModule
) extends ConfigurationModule with CommonServiceModule
