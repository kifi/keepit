package com.keepit.common.queue

import com.google.inject.{ Provides, Singleton }
import com.kifi.franz.{ FakeSQSQueue, SQSQueue }

@Singleton
case class FakeSimpleQueueModule() extends SimpleQueueModule {

  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def richConnectionUpdateQueue(): SQSQueue[RichConnectionUpdateMessage] = {
    new FakeSQSQueue[RichConnectionUpdateMessage] {}
  }

}
