package com.keepit.module

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3Client
import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports._
import com.keepit.common.logging.Logging
import com.keepit.common.social.S3SocialUserRawInfoStoreImpl
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.common.store.S3Bucket
import com.keepit.search._
import com.tzavellas.sse.guice.ScalaModule

import play.api.Play.current

class S3Module() extends ScalaModule with Logging {

  def configure() {

  }

  @Singleton
  @Provides
  def articleSearchResultStore(amazonS3Client: AmazonS3): ArticleSearchResultStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.articleSearch.bucket").get)
    new S3ArticleSearchResultStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def articleStore(amazonS3Client: AmazonS3): ArticleStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.article.bucket").get)
    new S3ArticleStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def socialUserRawInfoStore(amazonS3Client: AmazonS3): SocialUserRawInfoStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.social.bucket").get)
    new S3SocialUserRawInfoStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def eventStore(amazonS3Client: AmazonS3): EventStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.event.bucket").get)
    new S3EventStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def reportStore(amazonS3Client: AmazonS3): ReportStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.report.bucket").get)
    new S3ReportStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def amazonS3Client(): AmazonS3 = {
    val conf = current.configuration.getConfig("amazon.s3").get
    val awsCredentials = new BasicAWSCredentials(
      conf.getString("accessKey").get,
      conf.getString("secretKey").get)
    new AmazonS3Client(awsCredentials)
  }
}
