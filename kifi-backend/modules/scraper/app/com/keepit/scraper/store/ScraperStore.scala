package com.keepit.common.store

import com.google.inject.{ Provides, Singleton }
import com.amazonaws.services.s3.{ AmazonS3Client, AmazonS3 }
import com.keepit.common.healthcheck.{ SystemAdminMailSender, AirbrakeNotifier }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.time.Clock
import play.api.Play._
import com.keepit.common.aws.AwsModule
import com.keepit.common.logging.AccessLog
import com.keepit.learning.porndetector._
import com.google.inject.Provider
import com.keepit.scraper.embedly._

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
  def bayesPornDetectorStore(amazonS3Client: AmazonS3, accessLog: AccessLog): PornWordLikelihoodStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.bayes.porn.detector.bucket").get)
    new S3PornWordLikelihoodStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def embedlyStore(amazonS3Client: AmazonS3, accessLog: AccessLog): EmbedlyStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.embedly.bucket").get)
    new S3EmbedlyStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def uriImageStore(amazonS3Client: AmazonS3, config: S3ImageConfig, airbrake: AirbrakeNotifier): S3URIImageStore = {
    new S3URIImageStoreImpl(amazonS3Client, config, airbrake)
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
  def bayesPornDetectorStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog) = {
    whenConfigured("amazon.s3.bayes.porn.detector.bucket")(
      prodStoreModule.bayesPornDetectorStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryPornWordLikelihoodStore() {
        override def syncGet(key: String) = Some(PornWordLikelihood(Map("a" -> 1f)))
      })
  }

  @Singleton
  @Provides
  def embedlyStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): EmbedlyStore = {
    whenConfigured("amazon.s3.embedly.bucket")(
      prodStoreModule.embedlyStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryEmbedlyStoreImpl())
  }

  @Singleton
  @Provides
  def uriImageStore(amazonS3Client: AmazonS3, config: S3ImageConfig, airbrake: AirbrakeNotifier): S3URIImageStore = {
    new S3URIImageStoreImpl(amazonS3Client, config, airbrake)
  }
}
