package com.keepit.queue

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.google.inject.{ Singleton, Provides }
import com.keepit.common.queue.messages.{ SuggestedSearchTermsWithLibraryId, LibrarySuggestedSearchRequest }
import com.kifi.franz.{ FakeSQSQueue, SimpleSQSClient, QueueName, SQSQueue }
import net.codingwell.scalaguice.ScalaModule

trait LibrarySuggestedSearchQueueModule extends ScalaModule

case class ProdLibrarySuggestedSearchQueueModule() extends LibrarySuggestedSearchQueueModule {
  def configure() {}

  @Singleton @Provides
  def autotagRequestQueue(basicAWSCreds: BasicAWSCredentials): SQSQueue[LibrarySuggestedSearchRequest] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)
    val name = QueueName("library-autotag-request-prod")
    client.formatted[LibrarySuggestedSearchRequest](name)
  }

  @Singleton @Provides
  def suggestedSearchTermsQueue(basicAWSCreds: BasicAWSCredentials): SQSQueue[SuggestedSearchTermsWithLibraryId] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)
    val name = QueueName("library-autotag-result-prod")
    client.formatted[SuggestedSearchTermsWithLibraryId](name)
  }

}

case class DevLibrarySuggestedSearchQueueModule() extends LibrarySuggestedSearchQueueModule {
  def configure() {}

  @Singleton @Provides
  def autotagRequestQueue(): SQSQueue[LibrarySuggestedSearchRequest] = new FakeSQSQueue[LibrarySuggestedSearchRequest] {}

  @Singleton @Provides
  def suggestedSearchTermsQueue(): SQSQueue[SuggestedSearchTermsWithLibraryId] = new FakeSQSQueue[SuggestedSearchTermsWithLibraryId] {}
}
