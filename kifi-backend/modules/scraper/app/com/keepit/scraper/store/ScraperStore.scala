package com.keepit.common.store

import com.google.inject.{ Provides, Singleton }
import com.amazonaws.services.s3.{ AmazonS3 }
import com.keepit.scraper.store.UriImageStoreInbox
import org.apache.commons.io.FileUtils
import play.api.Play._
import com.keepit.common.logging.AccessLog
import com.google.inject.Provider
import com.keepit.scraper.embedly._

case class ScraperProdStoreModule() extends ProdStoreModule {
  def configure() {
  }

  @Provides @Singleton
  def uriImageStoreInbox: UriImageStoreInbox = {
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("scraper.temporary.directory").get, "uri_images")
    UriImageStoreInbox(inboxDir)
  }

  @Singleton
  @Provides
  def embedlyStore(amazonS3Client: AmazonS3, accessLog: AccessLog): EmbedlyStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.embedly.bucket").get)
    new S3EmbedlyStoreImpl(bucketName, amazonS3Client, accessLog)
  }
}

case class ScraperDevStoreModule() extends DevStoreModule(ScraperProdStoreModule()) {
  def configure() {
  }

  @Provides @Singleton
  def uriImageStoreInbox: UriImageStoreInbox = whenConfigured("scraper.temporary.directory")(prodStoreModule.uriImageStoreInbox) getOrElse {
    UriImageStoreInbox(FileUtils.getTempDirectory)
  }

  @Singleton
  @Provides
  def embedlyStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): EmbedlyStore = {
    whenConfigured("amazon.s3.embedly.bucket")(
      prodStoreModule.embedlyStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryEmbedlyStoreImpl())
  }
}
