package com.keepit.common.store

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provider, Provides, Singleton }
import com.keepit.common.logging.{ Logging, AccessLog }
import com.keepit.inject.AppScoped
import com.keepit.social.{ InMemorySocialUserRawInfoStoreImpl, S3SocialUserRawInfoStoreImpl, SocialUserRawInfoStore }
import com.keepit.typeahead._
import org.apache.commons.io.FileUtils

import play.api.Play.current

trait ShoeboxStoreModule extends StoreModule with Logging

case class ShoeboxProdStoreModule() extends ProdStoreModule with ShoeboxStoreModule {
  def configure() {
    bind[RoverImageStore].to[S3RoverImageStoreImpl]
    bind[OrganizationAvatarStore].to[S3OrganizationAvatarStoreImpl]
  }

  @Provides @Singleton
  def roverImageStoreInbox: RoverImageStoreInbox = {
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("shoebox.temporary.directory").get, "images")
    RoverImageStoreInbox(inboxDir)
  }

  @Provides @Singleton
  def organizationAvatarStoreInbox: OrganizationAvatarStoreInbox = {
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("shoebox.temporary.directory").get, "organization-avatars")
    OrganizationAvatarStoreInbox(inboxDir)
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

}

case class ShoeboxDevStoreModule() extends DevStoreModule(ShoeboxProdStoreModule()) with ShoeboxStoreModule {
  def configure() {
    bind[RoverImageStore].to[InMemoryRoverImageStoreImpl]
    bind[OrganizationAvatarStore].to[InMemoryOrganizationAvatarStoreImpl]
  }

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
}
