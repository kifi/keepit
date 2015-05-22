package com.keepit.rover.manager

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.google.inject.{ Singleton, Provides }
import com.keepit.common.logging.Logging
import com.kifi.franz._
import net.codingwell.scalaguice.{ ScalaMultibinder, ScalaModule }

abstract class RoverQueueModule extends ScalaModule {
  def configure(): Unit = {
    import FetchTaskQueue._
    val fetchQueueBinder = ScalaMultibinder.newSetBinder[FetchTaskQueue](binder)
    fetchQueueBinder.addBinding.to[TopPriority]
    fetchQueueBinder.addBinding.to[FirstTime]
    fetchQueueBinder.addBinding.to[NewVersion]
    fetchQueueBinder.addBinding.to[Refresh]

    import ArticleImageProcessingTaskQueue._
    val articleImageProcessingQueueBinder = ScalaMultibinder.newSetBinder[ArticleImageProcessingTaskQueue](binder)
    articleImageProcessingQueueBinder.addBinding.to[FastFollow]
    articleImageProcessingQueueBinder.addBinding.to[CatchUp]
  }

}

case class ProdRoverQueueModule() extends RoverQueueModule with Logging {

  private def makeSQSClient(basicAWSCreds: BasicAWSCredentials) = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)

  @Provides @Singleton
  def sqsClient(basicAWSCreds: BasicAWSCredentials): SQSClient = makeSQSClient(basicAWSCreds)

  @Provides @Singleton
  def topPriorityQueue(basicAWSCreds: BasicAWSCredentials): FetchTaskQueue.TopPriority = {
    val client = makeSQSClient(basicAWSCreds) // this queue does not share its client
    val name = QueueName("rover-top-priority-fetch-task-prod")
    val queue = client.formatted[FetchTask](name)
    FetchTaskQueue.TopPriority(queue)
  }

  @Provides @Singleton
  def firstTimeQueue(client: SQSClient): FetchTaskQueue.FirstTime = {
    val name = QueueName("rover-first-time-fetch-task-prod")
    val queue = client.formatted[FetchTask](name)
    FetchTaskQueue.FirstTime(queue)
  }

  @Provides @Singleton
  def newVersionQueue(client: SQSClient): FetchTaskQueue.NewVersion = {
    val name = QueueName("rover-new-version-fetch-task-prod")
    val queue = client.formatted[FetchTask](name)
    FetchTaskQueue.NewVersion(queue)
  }

  @Provides @Singleton
  def refreshQueue(client: SQSClient): FetchTaskQueue.Refresh = {
    val name = QueueName("rover-refresh-fetch-task-prod")
    val queue = client.formatted[FetchTask](name)
    FetchTaskQueue.Refresh(queue)
  }

  @Provides @Singleton
  def fastFollowQueue(client: SQSClient): ArticleImageProcessingTaskQueue.FastFollow = {
    val name = QueueName("rover-fast-follow-image-processing-task-prod")
    val queue = client.formatted[ArticleImageProcessingTask](name)
    ArticleImageProcessingTaskQueue.FastFollow(queue)
  }

  @Provides @Singleton
  def catchUpQueue(client: SQSClient): ArticleImageProcessingTaskQueue.CatchUp = {
    val name = QueueName("rover-catch-up-image-processing-task-prod")
    val queue = client.formatted[ArticleImageProcessingTask](name)
    ArticleImageProcessingTaskQueue.CatchUp(queue)
  }

}

@Singleton
case class DevRoverQueueModule() extends RoverQueueModule with Logging {

  @Provides @Singleton
  def topPriorityQueue: FetchTaskQueue.TopPriority = FetchTaskQueue.TopPriority(new FakeSQSQueue[FetchTask] {})

  @Provides @Singleton
  def firstFetchQueue: FetchTaskQueue.FirstTime = FetchTaskQueue.FirstTime(new FakeSQSQueue[FetchTask] {})

  @Provides @Singleton
  def fetchNewVersionQueue: FetchTaskQueue.NewVersion = FetchTaskQueue.NewVersion(new FakeSQSQueue[FetchTask] {})

  @Provides @Singleton
  def refetchQueue: FetchTaskQueue.Refresh = FetchTaskQueue.Refresh(new FakeSQSQueue[FetchTask] {})

  @Provides @Singleton
  def fastFollowQueue: ArticleImageProcessingTaskQueue.FastFollow = ArticleImageProcessingTaskQueue.FastFollow(new FakeSQSQueue[ArticleImageProcessingTask] {})

  @Provides @Singleton
  def catchUpQueue: ArticleImageProcessingTaskQueue.CatchUp = ArticleImageProcessingTaskQueue.CatchUp(new FakeSQSQueue[ArticleImageProcessingTask] {})
}
