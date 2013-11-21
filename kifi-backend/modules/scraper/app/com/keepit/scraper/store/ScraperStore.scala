package com.keepit.common.store

import com.google.inject.{Provides, Singleton}
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.time.Clock

import play.api.Play._

case class ScraperProdStoreModule() extends ProdStoreModule {
  def configure() {
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
  def screenshotStore(amazonS3Client: AmazonS3, shoeboxServiceClient: ShoeboxServiceClient,
      airbrake: AirbrakeNotifier, clock: Clock, config: S3ImageConfig): S3ScreenshotStore = {
    new S3ScreenshotStoreImpl(amazonS3Client, shoeboxServiceClient: ShoeboxServiceClient, airbrake, clock, config)
  }


}

case class ScraperDevStoreModule() extends DevStoreModule(ScraperProdStoreModule()) {
  def configure() {
  }

  @Singleton
  @Provides
  def s3ImageConfig: S3ImageConfig =
    whenConfigured("cdn.bucket")(prodStoreModule.s3ImageConfig).getOrElse(S3ImageConfig("", "http://dev.ezkeep.com:9000", true))

  @Singleton
  @Provides
  def screenshotStore(amazonS3Client: AmazonS3, shoeboxServiceClient: ShoeboxServiceClient,
      airbrake: AirbrakeNotifier, clock: Clock, config: S3ImageConfig): S3ScreenshotStore = {
    new S3ScreenshotStoreImpl(amazonS3Client, shoeboxServiceClient: ShoeboxServiceClient, airbrake, clock, config)
  }

}
