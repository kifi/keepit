package com.keepit.common.store

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provider, Provides, Singleton }
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.export.{ KifiExportInbox, KifiExportConfig, S3KifiExportStore }
import com.keepit.social.{ InMemorySocialUserRawInfoStoreImpl, S3SocialUserRawInfoStoreImpl, SocialUserRawInfoStore }
import com.keepit.typeahead._
import play.api.Play.current

trait ShoeboxStoreModule extends StoreModule with Logging

case class ShoeboxProdStoreModule() extends ProdStoreModule with ShoeboxStoreModule {
  def configure() {
    bind[RoverImageStore].to[S3RoverImageStoreImpl]
  }

  @Provides @Singleton
  def roverImageStoreInbox: RoverImageStoreInbox = {
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("shoebox.temporary.directory").get, "images")
    RoverImageStoreInbox(inboxDir)
  }

  @Provides @Singleton
  def kifiExportInbox: KifiExportInbox = {
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("shoebox.temporary.directory").get, "exports")
    KifiExportInbox(inboxDir)
  }
  @Provides @Singleton
  def kifiExportConfig: KifiExportConfig = {
    val bucket = S3Bucket(current.configuration.getString("amazon.s3.exports.bucket").get)
    KifiExportConfig(bucket)
  }

  @Singleton
  @Provides
  def socialUserRawInfoStore(amazonS3Client: AmazonS3, accessLog: AccessLog): SocialUserRawInfoStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.social.bucket").get)
    new S3SocialUserRawInfoStoreImpl(bucketName, amazonS3Client, accessLog)
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
  def libraryTypeaheadStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LibraryTypeaheadStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.typeahead.library.bucket").get)
    new S3LibraryTypeaheadStore(bucketName, amazonS3Client, accessLog)
  }
}

case class ShoeboxDevStoreModule() extends DevStoreModule(ShoeboxProdStoreModule()) with ShoeboxStoreModule {
  def configure() {
    bind[RoverImageStore].to[InMemoryRoverImageStoreImpl]
  }

  @Provides @Singleton
  def kifiExportInbox: KifiExportInbox = prodStoreModule.kifiExportInbox

  @Provides @Singleton
  def kifiExportConfig: KifiExportConfig = prodStoreModule.kifiExportConfig

  @Singleton
  @Provides
  def socialUserRawInfoStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): SocialUserRawInfoStore =
    whenConfigured("amazon.s3.social.bucket")(
      prodStoreModule.socialUserRawInfoStore(amazonS3ClientProvider.get, accessLog)
    ).getOrElse(new InMemorySocialUserRawInfoStoreImpl())

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
  def libraryTypeaheadStore(amazonS3Client: AmazonS3, accessLog: AccessLog): LibraryTypeaheadStore = {
    whenConfigured("amazon.s3.typeahead.library.bucket")(
      prodStoreModule.libraryTypeaheadStore(amazonS3Client, accessLog)
    ) getOrElse (new InMemoryLibraryTypeaheadStoreImpl())
  }
}
