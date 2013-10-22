package com.keepit.common.store

import com.google.inject.{Provider, Provides, Singleton}
import com.amazonaws.services.s3.AmazonS3
import play.api.Play._
import com.keepit.search._
import com.keepit.search.BrowsingHistoryBuilder

case class SearchProdStoreModule() extends ProdStoreModule {
  def configure {}

  @Provides @Singleton
  def browsingHistoryStore(amazonS3Client: AmazonS3, builder: BrowsingHistoryBuilder): BrowsingHistoryStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.browsingHistory.bucket").get)
    new S3BrowsingHistoryStoreImpl(bucketName, amazonS3Client, builder)
  }

  @Provides @Singleton
  def clickHistoryStore(amazonS3Client: AmazonS3, builder: ClickHistoryBuilder): ClickHistoryStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.clickHistory.bucket").get)
    new S3ClickHistoryStoreImpl(bucketName, amazonS3Client, builder)
  }
}

case class SearchDevStoreModule() extends DevStoreModule(SearchProdStoreModule()) {
  def configure() {}

  @Provides @Singleton
  def browsingHistoryStore(amazonS3Client: AmazonS3, builder: BrowsingHistoryBuilder): BrowsingHistoryStore = {
    whenConfigured("amazon.s3.browsingHistory.bucket")(
      prodStoreModule.browsingHistoryStore(amazonS3Client, builder)
    ).getOrElse(new InMemoryBrowsingHistoryStoreImpl())
  }

  @Provides @Singleton
  def clickHistoryStore(amazonS3Client: AmazonS3, builder: ClickHistoryBuilder): ClickHistoryStore = {
    whenConfigured("amazon.s3.clickHistory.bucket")(
      prodStoreModule.clickHistoryStore(amazonS3Client, builder)
    ).getOrElse(new InMemoryClickHistoryStoreImpl())
  }
}