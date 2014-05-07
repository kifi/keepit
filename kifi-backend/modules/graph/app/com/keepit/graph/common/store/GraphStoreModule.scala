package com.keepit.graph.common.store

import com.google.inject.{Singleton, Provides}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.{Logging, AccessLog}
import play.api.Play._
import java.io.File
import org.apache.commons.io.FileUtils
import com.keepit.graph.manager._
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.common.amazon.AmazonInstanceInfo
import com.kifi.franz.{QueueName, SimpleSQSClient, SQSQueue}
import com.keepit.graph.manager.GraphStoreInbox
import com.amazonaws.regions.Regions
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.store.{DevStoreModule, S3Bucket, ProdStoreModule, StoreModule}
import com.keepit.common.time._

trait GraphStoreModule extends StoreModule with Logging {

  protected def getGraphUpdateQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo)(queuePrefix: String): SQSQueue[GraphUpdate] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered=false)
    val thisInstanceQueuePrefix = queuePrefix + "-" + amazonInstanceInfo.instanceId.id
    val deletedQueues = Await.result(client.deleteByPrefix(thisInstanceQueuePrefix), 5 minutes)
    log.info(s"$deletedQueues GraphUpdate queues belonging to Graph instance ${amazonInstanceInfo.instanceId} have been deleted.")
    val queue = client.formatted[GraphUpdate](QueueName(thisInstanceQueuePrefix + "-" + currentDateTime), true)
    queue
  }
}

case class GraphProdStoreModule() extends ProdStoreModule with GraphStoreModule {
  def configure {}

  @Provides @Singleton
  def graphStore(amazonS3Client: AmazonS3, accessLog: AccessLog): GraphStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.graph.bucket").get)
    val inboxDir = new File(current.configuration.getString("graph.temporary.directory").get, "s3").getCanonicalFile
    FileUtils.deleteDirectory(inboxDir)
    FileUtils.forceMkdir(inboxDir)
    inboxDir.deleteOnExit()
    new S3GraphStoreImpl(bucketName, amazonS3Client, accessLog, GraphStoreInbox(inboxDir))
  }

  @Provides @Singleton
  def graphUpdateQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo): SQSQueue[GraphUpdate] = {
    getGraphUpdateQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo)("graph-update-prod-b")
  }
}

case class GraphDevStoreModule() extends DevStoreModule(GraphProdStoreModule()) with GraphStoreModule {
  def configure() {}

  @Provides @Singleton
  def graphStore(amazonS3Client: AmazonS3, accessLog: AccessLog): GraphStore = {
    whenConfigured("amazon.s3.graph.bucket")(
      prodStoreModule.graphStore(amazonS3Client, accessLog)
    ).getOrElse(new InMemoryGraphStoreImpl())
  }

  @Provides @Singleton
  def graphUpdateQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo): SQSQueue[GraphUpdate] = {
    getGraphUpdateQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo)("graph-update-dev")
  }
}
