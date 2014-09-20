package com.keepit.search

import com.google.inject.{ Singleton, Provides }

case class FakeDistributedSearchServiceClientModule() extends DistributedSearchServiceClientModule {

  def configure() {}

  @Provides @Singleton
  def distributedSearchServiceClient: DistributedSearchServiceClient = new FakeDistributedSearchServiceClient()

}

class FakeDistributedSearchServiceClient() extends DistributedSearchServiceClientImpl(null, null, null, null)

