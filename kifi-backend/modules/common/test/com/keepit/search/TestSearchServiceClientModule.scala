package com.keepit.search

import com.google.inject.{Singleton, Provides}
import com.keepit.common.zookeeper.ServiceCluster

case class TestSearchServiceClientModule() extends SearchServiceClientModule {

  def configure() {}

  @Provides
  @Singleton
  def searchServiceClient(serviceCluster: ServiceCluster): SearchServiceClient = new SearchServiceClientImpl(serviceCluster, -1, null)

}
