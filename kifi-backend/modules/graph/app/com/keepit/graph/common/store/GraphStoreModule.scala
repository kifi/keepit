package com.keepit.graph.common.store

import com.google.inject.{ Singleton, Provides }
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.{ Logging, AccessLog }
import play.api.Play._
import java.io.File
import org.apache.commons.io.FileUtils
import com.keepit.graph.manager._
import com.keepit.graph.manager.GraphStoreInbox
import com.keepit.common.store.{ DevStoreModule, S3Bucket, ProdStoreModule, StoreModule }

trait GraphStoreModule extends StoreModule with Logging

case class GraphProdStoreModule() extends ProdStoreModule with GraphStoreModule {
  def configure {}

  @Provides @Singleton
  def graphStore(amazonS3Client: AmazonS3, accessLog: AccessLog): GraphStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.graph.bucket").get)
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("graph.temporary.directory").get, "s3")
    new S3GraphStoreImpl(bucketName, amazonS3Client, accessLog, GraphStoreInbox(inboxDir))
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
}
