package com.keepit.shoebox

import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.social.ShoeboxSecureSocialModule
import com.keepit.search.SearchServiceClientImplModule
import com.keepit.common.db.{DbInfo, SlickModule}
import com.keepit.scraper.ScraperImplModule
import com.keepit.common.social.SocialGraphImplModule
import com.keepit.common.analytics.AnalyticsImplModule
import com.keepit.learning.topicmodel.LdaTopicModelModule
import com.keepit.model.SliderHistoryTrackerImplModule
import com.keepit.common.mail.ShoeboxMailModule
import com.keepit.common.crypto.ShoeboxCryptoModule
import com.keepit.common.store.ShoeboxS3Module
import com.keepit.realtime.ShoeboxWebSocketModule
import com.keepit.classify.DomainTagImporterImplModule
import scala.slick.session.{Database => SlickDatabase}
import play.api.db.DB
import play.api.Play

case class ShoeboxProdModule() extends ShoeboxModule(
  // Common Functional Modules
  cacheModule = ShoeboxCacheModule(),
  secureSocialModule = ShoeboxSecureSocialModule(),
  searchServiceClientModule = SearchServiceClientImplModule(),
  clickHistoryModule = ShoeboxClickHistoryModule(),
  browsingHistoryModule = ShoeboxBrowsingHistoryModule(),
  mailModule = ShoeboxMailModule(),
  cryptoModule = ShoeboxCryptoModule(),
  s3Module = ShoeboxS3Module(),

  // Shoebox Functional Modules
  slickModule = SlickModule(ShoeboxDbInfo()),
  scraperModule = ScraperImplModule(),
  socialGraphModule = SocialGraphImplModule(),
  analyticsModule = AnalyticsImplModule(),
  webSocketModule = ShoeboxWebSocketModule(),
  topicModelModule = LdaTopicModelModule(),
  domainTagImporterModule = DomainTagImporterImplModule(),
  sliderHistoryTrackerModule = SliderHistoryTrackerImplModule()
)

case class ShoeboxDbInfo() extends DbInfo {
  def database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
  def driverName = Play.current.configuration.getString("db.shoebox.driver").get
}
