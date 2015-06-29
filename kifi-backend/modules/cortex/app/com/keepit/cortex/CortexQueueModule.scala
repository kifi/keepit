package com.keepit.cortex

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.queue.messages.{ LibrarySuggestedSearchRequest, SuggestedSearchTermsWithLibraryId }
import com.kifi.franz._
import net.codingwell.scalaguice.ScalaModule

abstract class CortexQueueModule extends ScalaModule

case class CortexProdQueueModule() extends CortexQueueModule {
  def configure() {}

  @Provides @Singleton
  def sqsClient(basicAWSCreds: BasicAWSCredentials): SQSClient = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)

  @Singleton @Provides
  def autotagRequestQueue(client: SQSClient): SQSQueue[LibrarySuggestedSearchRequest] = {
    val name = QueueName("library-autotag-request-prod")
    client.formatted[LibrarySuggestedSearchRequest](name)
  }

  @Singleton @Provides
  def suggestedSearchTermsQueue(client: SQSClient): SQSQueue[SuggestedSearchTermsWithLibraryId] = {
    val name = QueueName("library-autotag-result-prod")
    client.formatted[SuggestedSearchTermsWithLibraryId](name)
  }
}

case class CortexDevQueueModule() extends CortexQueueModule {
  def configure() {}

  @Singleton @Provides
  def autotagRequestQueue(): SQSQueue[LibrarySuggestedSearchRequest] = new FakeSQSQueue[LibrarySuggestedSearchRequest] {}

  @Singleton @Provides
  def suggestedSearchTermsQueue(): SQSQueue[SuggestedSearchTermsWithLibraryId] = new FakeSQSQueue[SuggestedSearchTermsWithLibraryId] {}
}
