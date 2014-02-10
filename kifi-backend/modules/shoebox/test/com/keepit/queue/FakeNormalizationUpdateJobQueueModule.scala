package com.keepit.queue

import com.google.inject.{Provides, Singleton}
import com.keepit.common.queue.SimpleQueueMessage

case class FakeNormalizationUpdateJobQueueModule() extends NormalizationUpdateJobQueueModule {

  val queueName = "barQ"

  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def normalizationUpdateTaskQ():NormalizationUpdateJobQueue = new NormalizationUpdateJobQueue {
    def name = queueName
    def queueUrl = "http://foo.com/barQ"
    def receive(): Seq[SimpleQueueMessage] = Seq.empty[SimpleQueueMessage]
    def send(s: String): Unit = {}
    def delete(msgHandle: String): Unit = {}
  }

}
