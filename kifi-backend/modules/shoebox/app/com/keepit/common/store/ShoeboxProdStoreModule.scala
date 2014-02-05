package com.keepit.common.store

import com.keepit.inject.AppScoped
import com.google.inject.{Provider, Provides, Singleton}
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.AccessLog
import com.keepit.common.time.Clock
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{InMemorySocialUserRawInfoStoreImpl, S3SocialUserRawInfoStoreImpl, SocialUserRawInfoStore}
import play.api.Play._
import com.keepit.common.aws.AwsModule

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
  def socialUserRawInfoStore(amazonS3Client: AmazonS3, acessLog: AccessLog): SocialUserRawInfoStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.social.bucket").get)
    new S3SocialUserRawInfoStoreImpl(bucketName, amazonS3Client, acessLog)
  }

  @Singleton
  @Provides
  def screenshotStore(amazonS3Client: AmazonS3, shoeboxServiceClient: ShoeboxServiceClient,
      airbrake: AirbrakeNotifier, clock: Clock, config: S3ImageConfig): S3ScreenshotStore = {
    new S3ScreenshotStoreImpl(amazonS3Client, shoeboxServiceClient: ShoeboxServiceClient, airbrake, clock, config)
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
  def socialUserRawInfoStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): SocialUserRawInfoStore =
    whenConfigured("amazon.s3.social.bucket")(
      prodStoreModule.socialUserRawInfoStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemorySocialUserRawInfoStoreImpl())

  @Singleton
  @Provides
  def screenshotStore(amazonS3Client: AmazonS3, shoeboxServiceClient: ShoeboxServiceClient,
      airbrake: AirbrakeNotifier, clock: Clock, config: S3ImageConfig): S3ScreenshotStore = {
    new S3ScreenshotStoreImpl(amazonS3Client, shoeboxServiceClient: ShoeboxServiceClient, airbrake, clock, config)
  }
}
