package com.keepit.common.queue

import com.google.inject.{Provides, Singleton}

case class FakeSimpleQueueModule() extends SimpleQueueModule {

  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def simpleQueueService():SimpleQueueService = new SimpleQueueService {
    override def getUrl(name: String): Option[String] = None
    override def create(name: String): String = name
    override def list(): Seq[String] = Seq.empty[String]
    override def delete(url: String): Unit = {}
    override def getByUrl(url: String): Option[SimpleQueue] = None
  }

  @Singleton
  @Provides
  def normalizationUpdateTaskQ():NormalizationUpdateJobQueue = new NormalizationUpdateJobQueue {
    override def queueUrl: String = "http://foo.com/barQ"
    override def receive(): Seq[SQSMessage] = Seq.empty[SQSMessage]
    override def send(s: String): Unit = {}
    override def delete(msgHandle: String): Unit = {}
  }

}