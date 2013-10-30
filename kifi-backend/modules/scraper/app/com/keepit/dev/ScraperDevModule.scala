package com.keepit.dev

import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.cache.ScraperCacheModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.inject.ProdFortyTwoModule
import com.keepit.common.actor.DevActorSystemModule
import com.keepit.common.zookeeper.DevDiscoveryModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.scraper.ScraperServiceModule
import com.keepit.common.healthcheck.ProdHealthCheckModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ScraperDevStoreModule

case class ScraperDevModule() extends ScraperServiceModule (
  // cacheModule = ScraperCacheModule(HashMapMemoryCacheModule()),
  storeModule = ScraperDevStoreModule()
) with CommonDevModule {

}
