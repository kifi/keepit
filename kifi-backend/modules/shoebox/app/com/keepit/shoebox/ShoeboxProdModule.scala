package com.keepit.shoebox

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ShoeboxCacheModule}
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.ProdAnalyticsModule
import com.keepit.learning.topicmodel.LdaTopicModelModule
import com.keepit.common.mail.ProdMailModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.classify.ProdDomainTagImporterModule
import com.keepit.inject.CommonProdModule
import com.keepit.common.integration.ProdReaperModule

case class ShoeboxProdModule() extends ShoeboxModule (
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  mailModule = ProdMailModule(),
  reaperModule = ProdReaperModule(),
  storeModule = ShoeboxDevStoreModule(),

  // Shoebox Functional Modules
  analyticsModule = ProdAnalyticsModule(),
  //topicModelModule = LdaTopicModelModule(), //disable for now
  domainTagImporterModule = ProdDomainTagImporterModule(),
  cacheModule = ShoeboxCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
) with CommonProdModule
