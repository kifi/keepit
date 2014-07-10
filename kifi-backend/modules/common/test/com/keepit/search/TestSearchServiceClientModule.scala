package com.keepit.search

import com.google.inject.{ Singleton, Provides }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.{ FakeHttpPostClient, FakeHttpClient }

case class TestSearchServiceClientModule() extends SearchServiceClientModule {

  def configure() {}

  @Provides
  @Singleton
  def searchServiceClient(serviceCluster: ServiceCluster, airbrakeNotifier: AirbrakeNotifier): SearchServiceClient = {
    new SearchServiceClientImpl(serviceCluster, new FakeHttpPostClient(None, s => ()), airbrakeNotifier)
  }
}
