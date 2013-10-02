package com.keepit.common.store

import com.google.inject.{Provider, Provides, Singleton}
import play.api.Play._
import com.amazonaws.services.s3.AmazonS3
import com.keepit.abook.store.{InMemoryABookRawInfoStoreImpl, S3ABookRawInfoStoreImpl, ABookRawInfoStore}

case class ABookProdStoreModule() extends ProdStoreModule {
  def configure() {
  }

  @Singleton
  @Provides
  def addressBookRawInfoStore(amazonS3Client: AmazonS3): ABookRawInfoStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.abook.bucket").get)
    new S3ABookRawInfoStoreImpl(bucketName, amazonS3Client)
  }

}

case class ABookDevStoreModule() extends DevStoreModule(ABookProdStoreModule()) {
  def configure() {
  }

  @Singleton
  @Provides
  def addressBookRawInfoStore(amazonS3ClientProvider: Provider[AmazonS3]): ABookRawInfoStore =
    whenConfigured("amazon.s3.social.bucket")(prodStoreModule.addressBookRawInfoStore(amazonS3ClientProvider.get)).getOrElse(new InMemoryABookRawInfoStoreImpl())
}
