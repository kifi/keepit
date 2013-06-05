package com.keepit.dev

import com.keepit.module.S3Module
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3Client
import com.google.inject.{Provides, Singleton, Provider}
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports._
import com.keepit.common.logging.Logging
import com.keepit.common.social.{InMemorySocialUserRawInfoStoreImpl, SocialUserRawInfoStore}
import com.keepit.common.store.{S3ImageConfig, S3Bucket}
import com.keepit.search._
import com.tzavellas.sse.guice.ScalaModule

import play.api.Play.current

class S3DevModule() extends ScalaModule with Logging {

  def configure() {

  }

  @Singleton
  @Provides
  def articleSearchResultStore(amazonS3ClientProvider: Provider[AmazonS3]): ArticleSearchResultStore =
    current.configuration.getString("amazon.s3.articleSearch.bucket") match {
      case Some(name) => new S3Module().articleSearchResultStore(amazonS3ClientProvider.get)
      case None => new InMemoryArticleSearchResultStoreImpl()
    }

  @Singleton
  @Provides
  def articleStore(amazonS3ClientProvider: Provider[AmazonS3]): ArticleStore =
    current.configuration.getString("amazon.s3.article.bucket") match {
      case Some(name) => new S3Module().articleStore(amazonS3ClientProvider.get)
      case None => new InMemoryArticleStoreImpl()
    }

  @Singleton
  @Provides
  def socialUserRawInfoStore(amazonS3ClientProvider: Provider[AmazonS3]): SocialUserRawInfoStore =
    current.configuration.getString("amazon.s3.sociel.bucket") match {
      case Some(name) => new S3Module().socialUserRawInfoStore(amazonS3ClientProvider.get)
      case None => new InMemorySocialUserRawInfoStoreImpl()
    }

  @Singleton
  @Provides
  def eventStore(amazonS3ClientProvider: Provider[AmazonS3]): EventStore =
    current.configuration.getString("amazon.s3.event.bucket") match {
      case Some(name) => new S3Module().eventStore(amazonS3ClientProvider.get)
      case None => new InMemoryS3EventStoreImpl()
    }

  @Singleton
  @Provides
  def reportStore(amazonS3ClientProvider: Provider[AmazonS3]): ReportStore =
    current.configuration.getString("amazon.s3.report.bucket") match {
      case Some(name) => new S3Module().reportStore(amazonS3ClientProvider.get)
      case None => new InMemoryReportStoreImpl()
    }

  @Singleton
  @Provides
  def s3ImageConfig: S3ImageConfig = {
    val bucket = current.configuration.getString("cdn.bucket")
    val base = current.configuration.getString("cdn.base")
    S3ImageConfig(
      if (base.isDefined) bucket.get else "",
      base.getOrElse("http://dev.ezkeep.com:9000"),
      base.isEmpty)
  }

}
