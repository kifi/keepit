package com.keepit.shoebox

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck._
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster

import scala.concurrent.ExecutionContext
import com.keepit.common.crypto.{ FakeCryptoModule, PublicIdConfiguration }

case class FakeShoeboxServiceClientModule() extends ShoeboxServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def shoeboxServiceClient(
    shoeboxCacheProvided: ShoeboxCacheProvider,
    httpClient: HttpClient,
    serviceCluster: ServiceCluster,
    airbrakeNotifier: AirbrakeNotifier,
    executionContext: ExecutionContext): ShoeboxServiceClient =
    new ShoeboxServiceClientImpl(serviceCluster, httpClient, airbrakeNotifier, shoeboxCacheProvided, executionContext)
}

case class FakeShoeboxServiceModule() extends ShoeboxServiceClientModule {
  override def configure(): Unit = {
    install(FakeAirbrakeModule())
    install(FakeCryptoModule())
  }

  @Singleton
  @Provides
  def shoeboxServiceClient(fakeClient: FakeShoeboxServiceClientImpl): ShoeboxServiceClient = fakeClient

  @Singleton
  @Provides
  def fakeShoeboxServiceClient(airbrakeNotifier: AirbrakeNotifier, publicIdCondig: PublicIdConfiguration): FakeShoeboxServiceClientImpl =
    new FakeShoeboxServiceClientImpl(airbrakeNotifier, publicIdCondig)
}
