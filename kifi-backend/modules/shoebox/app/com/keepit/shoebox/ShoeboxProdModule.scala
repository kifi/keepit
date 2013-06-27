package com.keepit.shoebox

import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.social.SecureSocialImplModule
import com.keepit.search.SearchServiceClientImplModule
import com.keepit.common.db.{DbInfo, SlickModule}
import com.keepit.scraper.ScraperImplModule
import com.keepit.common.social.SocialGraphImplModule
import com.keepit.common.analytics.AnalyticsImplModule
import com.keepit.learning.topicmodel.LdaTopicModelModule
import com.keepit.model.SliderHistoryTrackerImplModule
import com.keepit.common.mail.ShoeboxMailModule
import com.keepit.common.crypto.ShoeboxCryptoModule
import com.keepit.common.healthcheck.ShoeboxHealthCheckModule
import com.keepit.common.store.ShoeboxS3Module
import com.keepit.realtime.ShoeboxRealtimeModule
import com.keepit.classify.DomainTagImporterImplModule
import scala.slick.session.{Database => SlickDatabase}
import play.api.db.DB
import play.api.Play

case class ShoeboxProdModule() extends ShoeboxModule {

  // Common Functional Modules
  val cacheModule = ShoeboxCacheModule()
  val secureSocialModule = SecureSocialImplModule()
  val searchServiceClientModule = SearchServiceClientImplModule()
  val clickHistoryTrackerModule = ClickHistoryTrackerImplModule()
  val browsingHistoryTrackerModule = BrowsingHistoryTrackerImplModule()
  val mailModule = ShoeboxMailModule()
  val cryptoModule = ShoeboxCryptoModule()
  val healthCheckModule = ShoeboxHealthCheckModule()
  val s3Module = ShoeboxS3Module()

  // Shoebox Functional Modules
  val slickModule = SlickModule(ShoeboxDbInfo())
  val scraperModule = ScraperImplModule()
  val socialGraphModule = SocialGraphImplModule()
  val analyticsModule = AnalyticsImplModule()
  val realtimeModule = ShoeboxRealtimeModule()
  val topicModelModule = LdaTopicModelModule()
  val domainTagImporterModule = DomainTagImporterImplModule()
  val sliderHistoryTrackerModule = SliderHistoryTrackerImplModule()

}

case class ShoeboxDbInfo() extends DbInfo {
  def database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
  def driverName = Play.current.configuration.getString("db.shoebox.driver").get
}
