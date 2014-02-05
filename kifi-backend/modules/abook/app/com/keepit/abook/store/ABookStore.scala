package com.keepit.common.store

import com.google.inject.{Provider, Provides, Singleton}
import play.api.Play._
import com.amazonaws.services.s3.AmazonS3
import com.keepit.abook.store.{InMemoryABookRawInfoStoreImpl, S3ABookRawInfoStoreImpl, ABookRawInfoStore}
import com.keepit.common.logging.AccessLog
import com.keepit.common.aws.AwsModule

case class ABookProdStoreModule() extends ProdStoreModule {
  def configure() {
  }

  @Singleton
  @Provides
  def addressBookRawInfoStore(amazonS3Client: AmazonS3, accessLog: AccessLog): ABookRawInfoStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.abook.bucket").get)
    new S3ABookRawInfoStoreImpl(bucketName, amazonS3Client, accessLog)
  }

}

case class ABookDevStoreModule() extends DevStoreModule(ABookProdStoreModule()) {
  def configure() { }

  @Singleton
  @Provides
  def addressBookRawInfoStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): ABookRawInfoStore =
    whenConfigured("amazon.s3.abook.bucket")(prodStoreModule.addressBookRawInfoStore(amazonS3ClientProvider.get, accessLog)).getOrElse(new InMemoryABookRawInfoStoreImpl())
}
