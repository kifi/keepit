package com.keepit.common.store

import com.keepit.inject.AppScoped
import com.google.inject.{Provider, Provides, Singleton}
import play.api.Play._
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.social.{InMemorySocialUserRawInfoStoreImpl, S3SocialUserRawInfoStoreImpl, SocialUserRawInfoStore}

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
}
