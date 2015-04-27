package com.keepit.rover.store

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.transfer.TransferManager
import com.google.inject.{ Provider, Provides, Singleton }
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{ S3ImageConfig, DevStoreModule, ProdStoreModule, S3Bucket }
import play.api.Play._

import scala.concurrent.ExecutionContext

trait RoverStoreModule

case class RoverProdStoreModule() extends ProdStoreModule with RoverStoreModule {
  def configure() = {}

  @Provides @Singleton
  def roverArticleStore(amazonS3Client: AmazonS3, accessLog: AccessLog): RoverUnderlyingArticleStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.rover.bucket").get)
    new S3RoverUnderlyingArticleStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Provides @Singleton
  def roverImageStoreInbox: RoverImageStoreInbox = {
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("rover.temporary.directory").get, "images")
    RoverImageStoreInbox(inboxDir)
  }

  @Provides @Singleton
  def roverImageStore(s3ImageConfig: S3ImageConfig, inbox: RoverImageStoreInbox, transferManager: TransferManager, executionContext: ExecutionContext): RoverImageStore = {
    new S3RoverImageStoreImpl(s3ImageConfig, inbox, transferManager, executionContext)
  }

}

case class RoverDevStoreModule() extends DevStoreModule(RoverProdStoreModule()) with RoverStoreModule {
  def configure() = {}

  @Provides @Singleton
  def roverArticleStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): RoverUnderlyingArticleStore =
    whenConfigured("amazon.s3.rover.bucket")(
      prodStoreModule.roverArticleStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryRoverUnderlyingArticleStoreImpl())

  @Provides @Singleton
  def roverImageStore(): RoverImageStore = new InMemoryRoverImageStoreImpl()
}
