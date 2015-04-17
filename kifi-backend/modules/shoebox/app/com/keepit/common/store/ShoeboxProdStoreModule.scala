package com.keepit.common.store

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provider, Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.AccessLog
import com.keepit.inject.AppScoped
import com.keepit.scraper.embedly.{ EmbedlyStore, InMemoryEmbedlyStoreImpl, S3EmbedlyStoreImpl }
import com.keepit.social.{ InMemorySocialUserRawInfoStoreImpl, S3SocialUserRawInfoStoreImpl, SocialUserRawInfoStore }
import com.keepit.typeahead._

import play.api.Play.current

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

  @Provides @Singleton
  def keepImageStoreInbox: KeepImageStoreInbox = {
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("shoebox.temporary.directory").get, "keep_images")
    KeepImageStoreInbox(inboxDir)
  }

  @Provides @Singleton
  def libraryImageStoreInbox: LibraryImageStoreInbox = {
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("shoebox.temporary.directory").get, "library_images")
    LibraryImageStoreInbox(inboxDir)
  }

  @Singleton
  @Provides
  def socialUserRawInfoStore(amazonS3Client: AmazonS3, accessLog: AccessLog): SocialUserRawInfoStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.social.bucket").get)
    new S3SocialUserRawInfoStoreImpl(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def uriImageStore(amazonS3Client: AmazonS3, config: S3ImageConfig, airbrake: AirbrakeNotifier): S3URIImageStore = {
    new S3URIImageStoreImpl(amazonS3Client, config, airbrake)
  }

  @Singleton
  @Provides
  def socialUserTypeaheadStore(amazonS3Client: AmazonS3, accessLog: AccessLog): SocialUserTypeaheadStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.typeahead.social.bucket").get)
    new S3SocialUserTypeaheadStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def kifiUserTypeaheadStore(amazonS3Client: AmazonS3, accessLog: AccessLog): KifiUserTypeaheadStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.typeahead.kifi.bucket").get)
    new S3KifiUserTypeaheadStore(bucketName, amazonS3Client, accessLog)
  }

  @Singleton
  @Provides
  def embedlyStore(amazonS3Client: AmazonS3, accessLog: AccessLog): EmbedlyStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.embedly.bucket").get)
    new S3EmbedlyStoreImpl(bucketName, amazonS3Client, accessLog)
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
  def uriImageStore(amazonS3Client: AmazonS3, config: S3ImageConfig, airbrake: AirbrakeNotifier): S3URIImageStore = {
    new S3URIImageStoreImpl(amazonS3Client, config, airbrake)
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
  def kifiUserTypeaheadStore(amazonS3Client: AmazonS3, accessLog: AccessLog): KifiUserTypeaheadStore = {
    whenConfigured("amazon.s3.typeahead.kifi.bucket")(
      prodStoreModule.kifiUserTypeaheadStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryKifiUserTypeaheadStoreImpl())
  }

  @Singleton
  @Provides
  def embedlyStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): EmbedlyStore = {
    whenConfigured("amazon.s3.embedly.bucket")(
      prodStoreModule.embedlyStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemoryEmbedlyStoreImpl())
  }

}
