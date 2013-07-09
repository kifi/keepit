package com.keepit.common.store

import com.keepit.inject.AppScoped
import com.google.inject.{Provider, Provides, Singleton}
import play.api.Play._
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.social.{InMemorySocialUserRawInfoStoreImpl, S3SocialUserRawInfoStoreImpl, SocialUserRawInfoStore}
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports.{InMemoryReportStoreImpl, S3ReportStoreImpl, ReportStore}
import com.mongodb.casbah.MongoConnection

case class ShoeboxProdStoreModule() extends ProdStoreModule {
  def configure() {
    bind[ImageDataIntegrityPlugin].to[ImageDataIntegrityPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def s3ImageConfig: S3ImageConfig = {
    val bucket = current.configuration.getString("cdn.bucket")
    val base = current.configuration.getString("cdn.base")
    S3ImageConfig(bucket.get, base.get)
  }

  @Singleton
  @Provides
  def socialUserRawInfoStore(amazonS3Client: AmazonS3): SocialUserRawInfoStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.social.bucket").get)
    new S3SocialUserRawInfoStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def eventStore(amazonS3Client: AmazonS3): EventStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.event.bucket").get)
    new S3EventStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def reportStore(amazonS3Client: AmazonS3): ReportStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.report.bucket").get)
    new S3ReportStoreImpl(bucketName, amazonS3Client)
  }
}

case class ShoeboxDevStoreModule() extends DevStoreModule(ShoeboxProdStoreModule()) {
  def configure() {
    bind[ImageDataIntegrityPlugin].to[ImageDataIntegrityPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def s3ImageConfig: S3ImageConfig =
    whenConfigured("cdn.bucket")(prodStoreModule.s3ImageConfig).getOrElse(S3ImageConfig("", "http://dev.ezkeep.com:9000", true))

  @Singleton
  @Provides
  def socialUserRawInfoStore(amazonS3ClientProvider: Provider[AmazonS3]): SocialUserRawInfoStore =
    whenConfigured("amazon.s3.social.bucket")(
      prodStoreModule.socialUserRawInfoStore(amazonS3ClientProvider.get)
    ).getOrElse(new InMemorySocialUserRawInfoStoreImpl())

  @Singleton
  @Provides
  def eventStore(amazonS3ClientProvider: Provider[AmazonS3]): EventStore =
    whenConfigured("amazon.s3.event.bucket")(
      prodStoreModule.eventStore(amazonS3ClientProvider.get)
    ).getOrElse(new InMemoryS3EventStoreImpl())

  @Singleton
  @Provides
  def reportStore(amazonS3ClientProvider: Provider[AmazonS3]): ReportStore =
    whenConfigured("amazon.s3.report.bucket")(
      prodStoreModule.reportStore(amazonS3ClientProvider.get)
    ).getOrElse(new InMemoryReportStoreImpl())
}
