package com.keepit.rover.store

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provider, Provides, Singleton }
import com.keepit.common.logging.AccessLog
import com.keepit.common.store._
import com.keepit.rover.sensitivity.{ PornWordLikelihood, InMemoryPornWordLikelihoodStore, PornWordLikelihoodStore, S3PornWordLikelihoodStore }
import play.api.Play._

trait RoverStoreModule

case class RoverProdStoreModule() extends ProdStoreModule with RoverStoreModule {
  def configure() = {
    bind[RoverImageStore].to[S3RoverImageStoreImpl]
  }

  @Provides @Singleton
  def roverArticleStore(amazonS3Client: AmazonS3, accessLog: AccessLog): RoverUnderlyingArticleStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.rover.bucket").get)
    new S3RoverUnderlyingArticleStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Provides @Singleton
  def roverImageStoreInbox: RoverImageStoreInbox = {
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("rover.temporary.directory").get, "images")
    RoverImageStoreInbox(inboxDir)
  }

  @Provides @Singleton
  def bayesPornDetectorStore(amazonS3Client: AmazonS3, accessLog: AccessLog): PornWordLikelihoodStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.bayes.porn.detector.bucket").get)
    new S3PornWordLikelihoodStore(bucketName, amazonS3Client, accessLog)
  }

}

case class RoverDevStoreModule() extends DevStoreModule(RoverProdStoreModule()) with RoverStoreModule {
  def configure() = {
    bind[RoverImageStore].to[InMemoryRoverImageStoreImpl]
  }

  @Provides @Singleton
  def roverArticleStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): RoverUnderlyingArticleStore =
    whenConfigured("amazon.s3.rover.bucket")(
      prodStoreModule.roverArticleStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryRoverUnderlyingArticleStoreImpl())

  @Provides @Singleton
  def bayesPornDetectorStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog) = {
    whenConfigured("amazon.s3.bayes.porn.detector.bucket")(
      prodStoreModule.bayesPornDetectorStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryPornWordLikelihoodStore() {
        override def syncGet(key: String) = Some(PornWordLikelihood(Map("a" -> 1f)))
      })
  }
}
