package com.keepit.queue

import com.google.inject.{ Provides, Singleton }
import com.kifi.franz.{ FakeSQSQueue, SQSQueue }

case class FakeNormalizationUpdateJobQueueModule() extends NormalizationUpdateJobQueueModule {

  val queueName = "barQ"

  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def normalizationUpdateQueue: SQSQueue[NormalizationUpdateTask] = new FakeSQSQueue[NormalizationUpdateTask] {}

}
