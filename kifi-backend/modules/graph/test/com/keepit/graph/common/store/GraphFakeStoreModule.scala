package com.keepit.graph.common.store

import com.keepit.common.store.FakeStoreModule
import com.google.inject.{ Singleton, Provides }
import com.keepit.graph.manager.{ GraphUpdate, InMemoryGraphStoreImpl, GraphStore }
import com.kifi.franz.{ FakeSQSQueue, SQSQueue }

case class GraphFakeStoreModule() extends FakeStoreModule with GraphStoreModule {

  @Provides @Singleton
  def graphStore(): GraphStore = new InMemoryGraphStoreImpl()

  @Provides @Singleton
  def graphUpdateQueue(): SQSQueue[GraphUpdate] = new FakeSQSQueue[GraphUpdate] {}

}
