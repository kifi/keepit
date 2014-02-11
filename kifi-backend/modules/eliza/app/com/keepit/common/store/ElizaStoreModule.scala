package com.keepit.common.store

import com.google.inject.{Provider, Singleton, Provides}
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3}
import com.keepit.common.logging.AccessLog
import play.api.Play.current
import com.amazonaws.auth.BasicAWSCredentials

case class ElizaProdStoreModule() extends StoreModule {
  def configure() {

  }

  @Singleton
  @Provides
  def amazonS3Client(basicAWSCredentials: BasicAWSCredentials): AmazonS3 = {
    new AmazonS3Client(basicAWSCredentials)
  }

  @Singleton
  @Provides
  def kifiInstallationStore(amazonS3Client: AmazonS3, accessLog: AccessLog): KifInstallationStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.install.bucket").get)
    new S3KifInstallationStoreImpl(bucketName, amazonS3Client, accessLog)
  }
}

case class ElizaDevStoreModule() extends StoreModule {
  def configure() {

  }
  protected def whenConfigured[T](parameter: String)(expression: => T): Option[T] =
    current.configuration.getString(parameter).map(_ => expression)

  @Singleton
  @Provides
  def amazonS3Client(basicAWSCredentials: BasicAWSCredentials): AmazonS3 = {
    new AmazonS3Client(basicAWSCredentials)
  }

  @Singleton
  @Provides
  def kifInstallationStore(amazonS3ClientProvider: Provider[AmazonS3], accessLog: AccessLog): KifInstallationStore =
    whenConfigured("amazon.s3.install.bucket") {
      val bucketName = S3Bucket(current.configuration.getString("amazon.s3.install.bucket").get)
      new S3KifInstallationStoreImpl(bucketName, amazonS3ClientProvider.get, accessLog)
    }.getOrElse(new InMemoryKifInstallationStoreImpl())
}
