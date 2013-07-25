package com.keepit.shoebox

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster

case class TestShoeboxServiceClientModule() extends ShoeboxServiceClientModule {

  def configure {}

  @Singleton
  @Provides
  def shoeboxServiceClient(
      shoeboxCacheProvided: ShoeboxCacheProvider,
      httpClient: HttpClient,
      serviceCluster: ServiceCluster,
      healthcheck: HealthcheckPlugin): ShoeboxServiceClient =
    new ShoeboxServiceClientImpl(serviceCluster, -1, httpClient,shoeboxCacheProvided, healthcheck)

}

case class FakeShoeboxServiceModule() extends ShoeboxServiceClientModule {
  def configure(): Unit = {}

  @Singleton
  @Provides
  def fakeShoeboxServiceClient(
      clickHistoryTracker: ClickHistoryTracker,
      browsingHistoryTracker: BrowsingHistoryTracker,
      healthcheck: HealthcheckPlugin): ShoeboxServiceClient =
    new FakeShoeboxServiceClientImpl(clickHistoryTracker, browsingHistoryTracker, healthcheck)

  @Provides
  @Singleton
  def fakeBrowsingHistoryTracker: BrowsingHistoryTracker =
    new FakeBrowsingHistoryTrackerImpl(3067, 2, 1)

  @Provides
  @Singleton
  def fakeClickHistoryTracker: ClickHistoryTracker =
    new FakeClickHistoryTrackerImpl(307, 2, 1)

}
