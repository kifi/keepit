package com.keepit.common.store

import com.google.inject.{Provides, Singleton}
import com.amazonaws.services.s3.AmazonS3
import play.api.Play._
import com.keepit.common.logging.AccessLog
import com.keepit.search._
import com.keepit.search.tracker.BrowsingHistoryBuilder
import com.keepit.search.index.{InMemoryIndexStoreImpl, IndexStore, S3IndexStoreImpl}
import java.io.File
import com.keepit.search.tracker.S3BrowsingHistoryStoreImpl
import com.keepit.search.tracker.InMemoryBrowsingHistoryStoreImpl
import com.keepit.search.tracker.BrowsingHistoryUserIdCache
import com.keepit.search.tracker.BrowsingHistoryStore
import com.keepit.search.tracker.S3ClickHistoryStoreImpl
import com.keepit.search.tracker.InMemoryClickHistoryStoreImpl
import com.keepit.search.tracker.ClickHistoryUserIdCache
import com.keepit.search.tracker.ClickHistoryStore
import com.keepit.search.tracker.ClickHistoryBuilder
import com.keepit.common.aws.AwsModule

case class SearchProdStoreModule() extends ProdStoreModule {
  def configure {
  }

  @Provides @Singleton
  def browsingHistoryStore(amazonS3Client: AmazonS3, accessLog: AccessLog, cache: BrowsingHistoryUserIdCache, builder: BrowsingHistoryBuilder): BrowsingHistoryStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.browsingHistory.bucket").get)
    new S3BrowsingHistoryStoreImpl(bucketName, amazonS3Client, accessLog, cache, builder)
  }

  @Provides @Singleton
  def clickHistoryStore(amazonS3Client: AmazonS3, accessLog: AccessLog, cache: ClickHistoryUserIdCache, builder: ClickHistoryBuilder): ClickHistoryStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.clickHistory.bucket").get)
    new S3ClickHistoryStoreImpl(bucketName, amazonS3Client, accessLog, cache, builder)
  }

  @Provides @Singleton
  def indexStore(amazonS3Client: AmazonS3, accessLog: AccessLog): IndexStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.index.bucket").get)
    new S3IndexStoreImpl(bucketName, amazonS3Client, accessLog)
  }
}

case class SearchDevStoreModule() extends DevStoreModule(SearchProdStoreModule()) {
  def configure() {
  }

  @Provides @Singleton
  def browsingHistoryStore(amazonS3Client: AmazonS3, accessLog: AccessLog, cache: BrowsingHistoryUserIdCache, builder: BrowsingHistoryBuilder): BrowsingHistoryStore = {
    whenConfigured("amazon.s3.browsingHistory.bucket")(
      prodStoreModule.browsingHistoryStore(amazonS3Client, accessLog, cache, builder)
    ).getOrElse(new InMemoryBrowsingHistoryStoreImpl())
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
