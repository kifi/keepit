package com.keepit.dev

import com.keepit.common.mail._
import com.keepit.shoebox._
import com.keepit.learning.topicmodel.DevTopicModelModule
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.classify.DevDomainTagImporterModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.DevAnalyticsModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.integration.DevReaperModule

case class ShoeboxDevModule() extends ShoeboxModule(
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  mailModule = DevMailModule(),
  reaperModule = DevReaperModule(),
  storeModule = ShoeboxDevStoreModule(),

  // Shoebox Functional Modules
  analyticsModule = DevAnalyticsModule(),
  topicModelModule = DevTopicModelModule(),
  domainTagImporterModule = DevDomainTagImporterModule(),
  cacheModule = ShoeboxCacheModule(HashMapMemoryCacheModule())
) with CommonDevModule

