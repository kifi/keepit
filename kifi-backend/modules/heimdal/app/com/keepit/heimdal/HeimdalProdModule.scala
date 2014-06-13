package com.keepit.heimdal

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.google.inject.{Provides, Singleton}
import com.amazonaws.auth.BasicAWSCredentials
import com.kifi.franz.{SimpleSQSClient, QueueName, SQSQueue}
import play.api.Play._
import com.keepit.common.cache.EhCacheCacheModule
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.cache.HeimdalCacheModule
import com.amazonaws.regions.Regions

case class HeimdalProdModule() extends HeimdalModule(
  cacheModule = HeimdalCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  mongoModule = ProdMongoModule(),
  heimdalQueueModule = HeimdalQueueProdModule()
) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: Nil
  }
}

