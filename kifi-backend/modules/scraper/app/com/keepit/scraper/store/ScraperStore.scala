package com.keepit.common.store

import com.google.inject.{Provides, Singleton}
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3}
import com.keepit.common.healthcheck.{SystemAdminMailSender, AirbrakeNotifier}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.time.Clock
import play.api.Play._
import com.keepit.common.aws.AwsModule
import com.keepit.common.logging.AccessLog
import com.keepit.learning.porndetector._
import com.google.inject.Provider
import com.keepit.common.embedly.EmbedlyClient

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
      airbrake: AirbrakeNotifier, clock: Clock, systemAdminMailSender:SystemAdminMailSender, config: S3ImageConfig, embedlyClient: EmbedlyClient): S3ScreenshotStore = {
    new S3ScreenshotStoreImpl(amazonS3Client, shoeboxServiceClient: ShoeboxServiceClient, airbrake, clock, embedlyClient, systemAdminMailSender, config)
  }

  @Singleton
  @Provides
  def bayesPornDetectorStore(amazonS3Client: AmazonS3, accessLog: AccessLog): PornWordLikelihoodStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.bayes.porn.detector.bucket").get)
    new S3PornWordLikelihoodStore(bucketName, amazonS3Client, accessLog)
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
      airbrake: AirbrakeNotifier, clock: Clock, systemAdminMailSender:SystemAdminMailSender, config: S3ImageConfig, embedlyClient: EmbedlyClient): S3ScreenshotStore = {
    new S3ScreenshotStoreImpl(amazonS3Client, shoeboxServiceClient: ShoeboxServiceClient, airbrake, clock, embedlyClient, systemAdminMailSender, config)
  }

  @Singleton
  @Provides
  def bayesPornDetectorStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog) ={
    whenConfigured("amazon.s3.bayes.porn.detector.bucket")(
      prodStoreModule.bayesPornDetectorStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryPornWordLikelihoodStore() {
      override def get(key: String) = Some(PornWordLikelihood(Map("a" -> 1f)))
    })
  }
}
