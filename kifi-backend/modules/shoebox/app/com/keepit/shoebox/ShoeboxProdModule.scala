package com.keepit.shoebox

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ShoeboxCacheModule}
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.ProdAnalyticsModule
import com.keepit.learning.topicmodel.LdaTopicModelModule
import com.keepit.common.mail.ProdMailModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.realtime.ShoeboxWebSocketModule
import com.keepit.classify.ProdDomainTagImporterModule
import com.keepit.inject.CommonProdModule

case class ShoeboxProdModule() extends ShoeboxModule(
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  mailModule = ProdMailModule(),
  storeModule = ShoeboxDevStoreModule(),

  // Shoebox Functional Modules
  analyticsModule = ProdAnalyticsModule(),
  webSocketModule = ShoeboxWebSocketModule(),
  topicModelModule = LdaTopicModelModule(),
  domainTagImporterModule = ProdDomainTagImporterModule(),
  cacheModule = ShoeboxCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
) with CommonProdModule
