package com.keepit.queue

import com.google.inject.{Provides, Singleton}
import com.keepit.common.queue.SimpleQueueMessage

case class FakeNormalizationUpdateJobQueueModule() extends NormalizationUpdateJobQueueModule {

  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def normalizationUpdateTaskQ():NormalizationUpdateJobQueue = new NormalizationUpdateJobQueue {
    override def queueUrl: String = "http://foo.com/barQ"
    override def receive(): Seq[SimpleQueueMessage] = Seq.empty[SimpleQueueMessage]
    override def send(s: String): Unit = {}
    override def delete(msgHandle: String): Unit = {}
  }

}
