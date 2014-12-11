package com.keepit.search.common.store

import com.google.inject.{ Provides, Singleton }
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.{ DevStoreModule, S3Bucket, ProdStoreModule }
import play.api.Play._
import com.keepit.common.logging.AccessLog
import com.keepit.search.index.{ IndexStoreInbox, InMemoryIndexStoreImpl, IndexStore, S3IndexStoreImpl }
import com.keepit.search.tracker.S3ClickHistoryStoreImpl
import com.keepit.search.tracker.InMemoryClickHistoryStoreImpl
import com.keepit.search.tracker.ClickHistoryUserIdCache
import com.keepit.search.tracker.ClickHistoryStore
import com.keepit.search.tracker.ClickHistoryBuilder
import java.io.File
import org.apache.commons.io.FileUtils

case class SearchProdStoreModule() extends ProdStoreModule {
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

case class SearchDevStoreModule() extends DevStoreModule(SearchProdStoreModule()) {
  def configure() {
  }

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
