package com.keepit.common.store

import com.keepit.inject.AppScoped
import com.google.inject.{Provider, Provides, Singleton}
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3}
import com.keepit.common.healthcheck.{SystemAdminMailSender, AirbrakeNotifier}
import com.keepit.common.logging.AccessLog
import com.keepit.common.time.Clock
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{InMemorySocialUserRawInfoStoreImpl, S3SocialUserRawInfoStoreImpl, SocialUserRawInfoStore}
import play.api.Play._
import com.keepit.typeahead.socialusers.{S3SocialUserTypeaheadStore, InMemorySocialUserTypeaheadStoreImpl, SocialUserTypeaheadStore}
import com.keepit.typeahead.abook.{InMemoryEContactTypeaheadStore, S3EContactTypeaheadStore, EContactTypeaheadStore}

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
  def socialUserRawInfoStore(amazonS3Client: AmazonS3, accessLog: AccessLog): SocialUserRawInfoStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.social.bucket").get)
    new S3SocialUserRawInfoStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def screenshotStore(amazonS3Client: AmazonS3, shoeboxServiceClient: ShoeboxServiceClient,
      airbrake: AirbrakeNotifier, clock: Clock, systemAdminMailSender:SystemAdminMailSender, config: S3ImageConfig): S3ScreenshotStore = {
    new S3ScreenshotStoreImpl(amazonS3Client, shoeboxServiceClient: ShoeboxServiceClient, airbrake, clock, systemAdminMailSender, config)
  }

  @Singleton
  @Provides
  def socialUserTypeaheadStore(amazonS3Client: AmazonS3, accessLog: AccessLog): SocialUserTypeaheadStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.typeahead.social.bucket").get)
    new S3SocialUserTypeaheadStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def econtactTypeaheadStore(amazonS3Client: AmazonS3, accessLog: AccessLog): EContactTypeaheadStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.typeahead.contact.bucket").get)
    new S3EContactTypeaheadStore(bucketName, amazonS3Client, accessLog)
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
      airbrake: AirbrakeNotifier, clock: Clock, systemAdminMailSender:SystemAdminMailSender, config: S3ImageConfig): S3ScreenshotStore = {
    new S3ScreenshotStoreImpl(amazonS3Client, shoeboxServiceClient: ShoeboxServiceClient, airbrake, clock, systemAdminMailSender, config)
  }

  @Singleton
  @Provides
  def socialUserTypeaheadStore(amazonS3Client: AmazonS3, accessLog: AccessLog): SocialUserTypeaheadStore = {
    whenConfigured("amazon.s3.typeahead.social.bucket")(
      prodStoreModule.socialUserTypeaheadStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemorySocialUserTypeaheadStoreImpl())
  }

  @Singleton
  @Provides
  def econtactTypeaheadStore(amazonS3Client: AmazonS3, accessLog: AccessLog): EContactTypeaheadStore = {
    whenConfigured("amazon.s3.typeahead.contact.bucket")(
      prodStoreModule.econtactTypeaheadStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryEContactTypeaheadStore())
  }

}
