package com.keepit.common.store

import com.google.inject.{Singleton, Provides}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import play.api.Play._
import java.io.File
import org.apache.commons.io.FileUtils
import com.keepit.graph.manager._
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.common.amazon.AmazonInstanceInfo
import com.kifi.franz.{QueueName, SimpleSQSClient, SQSQueue}
import com.keepit.graph.manager.GraphStoreInbox
import com.amazonaws.regions.Regions

trait GraphStoreModule extends StoreModule

case class GraphProdStoreModule() extends ProdStoreModule with GraphStoreModule {
  def configure {}

  @Provides @Singleton
  def graphStore(amazonS3Client: AmazonS3, accessLog: AccessLog): GraphStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.graph.bucket").get)
    val inboxDir = new File(current.configuration.getString("amazon.s3.graph.inbox").get).getCanonicalFile
    FileUtils.deleteDirectory(inboxDir)
    FileUtils.forceMkdir(inboxDir)
    inboxDir.deleteOnExit()
    new S3GraphStoreImpl(bucketName, amazonS3Client, accessLog, GraphStoreInbox(inboxDir))
  }

  @Provides @Singleton
  def graphUpdateQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo): SQSQueue[GraphUpdate] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered=false)
    client.formatted[GraphUpdate](QueueName("graph-update-prod-b-" + amazonInstanceInfo.instanceId.id), true)
  }
}

case class GraphDevStoreModule() extends DevStoreModule(GraphProdStoreModule()) with GraphStoreModule {
  def configure() {}

  @Provides @Singleton
  def indexStore(amazonS3Client: AmazonS3, accessLog: AccessLog): GraphStore = {
    whenConfigured("amazon.s3.graph.bucket")(
      prodStoreModule.graphStore(amazonS3Client, accessLog)
    ).getOrElse(new InMemoryGraphStoreImpl())
  }

  @Provides @Singleton
  def graphUpdateQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo): SQSQueue[GraphUpdate] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered=false)
    client.formatted[GraphUpdate](QueueName("graph-update-dev-" + amazonInstanceInfo.instanceId.id), true)
  }
}
