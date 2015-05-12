package com.keepit.search.common.store

import java.io.File

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.logging.{ Logging, AccessLog }
import com.keepit.common.store.{ StoreModule, DevStoreModule, ProdStoreModule, S3Bucket }
import com.keepit.search.index.{ InMemoryIndexStoreImpl, IndexStore, IndexStoreInbox, S3IndexStoreImpl }
import com.keepit.search.tracking.{ ClickHistoryBuilder, ClickHistoryStore, ClickHistoryUserIdCache, InMemoryClickHistoryStoreImpl, S3ClickHistoryStoreImpl }
import org.apache.commons.io.FileUtils
import play.api.Play._

trait SearchStoreModule extends StoreModule with Logging

case class SearchProdStoreModule() extends ProdStoreModule with SearchStoreModule {
  def configure {
  }

  @Provides @Singleton
  def clickHistoryStore(amazonS3Client: AmazonS3, accessLog: AccessLog, cache: ClickHistoryUserIdCache, builder: ClickHistoryBuilder): ClickHistoryStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.clickHistory.bucket").get)
    new S3ClickHistoryStoreImpl(bucketName, amazonS3Client, accessLog, cache, builder)
  }

  @Provides @Singleton
  def indexStore(amazonS3Client: AmazonS3, accessLog: AccessLog): IndexStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.index.bucket").get)
    val inboxDir = new File(current.configuration.getString("search.temporary.directory").get, "s3").getCanonicalFile
    FileUtils.deleteDirectory(inboxDir)
    FileUtils.forceMkdir(inboxDir)
    inboxDir.deleteOnExit()
    new S3IndexStoreImpl(bucketName, amazonS3Client, accessLog, IndexStoreInbox(inboxDir))
  }
}

case class SearchDevStoreModule() extends DevStoreModule(SearchProdStoreModule()) with SearchStoreModule {
  def configure() {}

  @Provides @Singleton
  def clickHistoryStore(amazonS3Client: AmazonS3, accessLog: AccessLog, cache: ClickHistoryUserIdCache, builder: ClickHistoryBuilder): ClickHistoryStore = {
    whenConfigured("amazon.s3.clickHistory.bucket")(
      prodStoreModule.clickHistoryStore(amazonS3Client, accessLog, cache, builder)
    ).getOrElse(new InMemoryClickHistoryStoreImpl())
  }

  @Provides @Singleton
  def indexStore(amazonS3Client: AmazonS3, accessLog: AccessLog): IndexStore = {
    whenConfigured("amazon.s3.index.bucket")(
      prodStoreModule.indexStore(amazonS3Client, accessLog)
    ).getOrElse(new InMemoryIndexStoreImpl())
  }
}
