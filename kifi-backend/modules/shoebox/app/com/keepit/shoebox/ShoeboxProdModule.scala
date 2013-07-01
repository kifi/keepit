package com.keepit.shoebox

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ShoeboxCacheModule}
import com.keepit.social.ShoeboxSecureSocialModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.common.db.{DbInfo, SlickModule}
import com.keepit.scraper.ScraperImplModule
import com.keepit.common.social.SocialGraphImplModule
import com.keepit.common.analytics.ProdAnalyticsModule
import com.keepit.learning.topicmodel.LdaTopicModelModule
import com.keepit.model.ProdSliderHistoryTrackerModule
import com.keepit.common.mail.ProdMailModule
import com.keepit.common.crypto.ShoeboxCryptoModule
import com.keepit.common.store.ShoeboxProdStoreModule
import com.keepit.realtime.ShoeboxWebSocketModule
import com.keepit.classify.ProdDomainTagImporterModule
import scala.slick.session.{Database => SlickDatabase}
import play.api.db.DB
import play.api.Play
import com.keepit.common.healthcheck.HealthCheckProdModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.inject.FortyTwoModule
import com.keepit.common.actor.ProdActorSystemModule
import com.keepit.common.zookeeper.ProdDiscoveryModule

case class ShoeboxProdModule() extends ShoeboxModule(
  // Common Functional Modules
  fortyTwoModule = FortyTwoModule(),
  cacheModule = ShoeboxCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  secureSocialModule = ShoeboxSecureSocialModule(),
  searchServiceClientModule = ProdSearchServiceClientModule(),
  clickHistoryModule = ShoeboxClickHistoryModule(),
  browsingHistoryModule = ShoeboxBrowsingHistoryModule(),
  mailModule = ProdMailModule(),
  cryptoModule = ShoeboxCryptoModule(),
  storeModule = ShoeboxProdStoreModule(),
  actorSystemModule = ProdActorSystemModule(),
  discoveryModule = ProdDiscoveryModule(),
  healthCheckModule = HealthCheckProdModule(),
  httpClientModule = ProdHttpClientModule(),

  // Shoebox Functional Modules
  slickModule = SlickModule(ShoeboxDbInfo()),
  scraperModule = ScraperImplModule(),
  socialGraphModule = SocialGraphImplModule(),
  analyticsModule = ProdAnalyticsModule(),
  webSocketModule = ShoeboxWebSocketModule(),
  topicModelModule = LdaTopicModelModule(),
  domainTagImporterModule = ProdDomainTagImporterModule(),
  sliderHistoryTrackerModule = ProdSliderHistoryTrackerModule()
)

case class ShoeboxDbInfo() extends DbInfo {
  def database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
  def driverName = Play.current.configuration.getString("db.shoebox.driver").get
}
