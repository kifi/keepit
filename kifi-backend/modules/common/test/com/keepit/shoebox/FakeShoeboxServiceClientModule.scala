package com.keepit.shoebox

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck._
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster

case class FakeShoeboxServiceClientModule() extends ShoeboxServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def shoeboxServiceClient(
    shoeboxCacheProvided: ShoeboxCacheProvider,
    httpClient: HttpClient,
    serviceCluster: ServiceCluster,
    airbrakeNotifier: AirbrakeNotifier): ShoeboxServiceClient =
    new ShoeboxServiceClientImpl(serviceCluster, httpClient, airbrakeNotifier, shoeboxCacheProvided)
}

case class FakeShoeboxScraperClientModule() extends ShoeboxScraperClientModule {

  def configure() {}

  @Singleton
  @Provides
  def shoeboxScraperClient(
    httpClient: HttpClient,
    serviceCluster: ServiceCluster,
    airbrakeNotifier: AirbrakeNotifier): ShoeboxScraperClient =
    new ShoeboxScraperClientImpl(serviceCluster, httpClient, airbrakeNotifier)
}

case class FakeShoeboxServiceModule() extends ShoeboxServiceClientModule {
  override def configure(): Unit = {
    install(FakeAirbrakeModule())
  }

  @Singleton
  @Provides
  def shoeboxScraperClient(airbrakeNotifier: AirbrakeNotifier): ShoeboxScraperClient =
    new FakeShoeboxScraperClientImpl(airbrakeNotifier)

  @Singleton
  @Provides
  def shoeboxServiceClient(airbrakeNotifier: AirbrakeNotifier): ShoeboxServiceClient =
    fakeShoeboxServiceClient(airbrakeNotifier)

  @Singleton
  @Provides
  def fakeShoeboxServiceClient(airbrakeNotifier: AirbrakeNotifier): FakeShoeboxServiceClientImpl =
    new FakeShoeboxServiceClientImpl(airbrakeNotifier)
}
