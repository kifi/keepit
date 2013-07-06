package com.keepit.common.store

import scala.collection.mutable.HashMap
import scala.concurrent.{Future, promise}

import com.amazonaws.services.s3.model.PutObjectResult
import com.google.inject.{Provider, Singleton, Provides}
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.social.SocialUserRawInfo
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.model.{User, NormalizedURI, SocialUserInfo}
import com.keepit.search.{InMemoryArticleSearchResultStoreImpl, ArticleSearchResultStore, Article, ArticleStore}
import com.keepit.inject.AppScoped
import com.keepit.common.analytics.{FakeMongoS3EventStore, MongoEventStore}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.analytics.reports.{InMemoryReportStoreImpl, ReportStore}

case class FakeStoreModule() extends StoreModule {

  def configure(): Unit = {
    bind[ArticleStore].toInstance(new HashMap[Id[NormalizedURI], Article] with ArticleStore)
    bind[SocialUserRawInfoStore].toInstance(new HashMap[Id[SocialUserInfo], SocialUserRawInfo] with SocialUserRawInfoStore)
    bind[S3ImageConfig].toInstance(S3ImageConfig("test-bucket", "http://localhost", isLocal = true))
  }

  @Provides @Singleton
  def s3ImageStore(s3ImageConfig: S3ImageConfig): S3ImageStore = FakeS3ImageStore(s3ImageConfig)

  @Provides @Singleton
  def s3ScreenshotStore = FakeS3ScreenshotStore()

  @Provides @Singleton
  def fakeMongoStore() : MongoEventStore = new FakeMongoS3EventStore()

  @Provides @Singleton
  def reportStore(): ReportStore = new InMemoryReportStoreImpl()

  @Provides @Singleton
  def articleSearchResultStore(): ArticleSearchResultStore = new InMemoryArticleSearchResultStoreImpl()

}

case class FakeS3ScreenshotStore() extends S3ScreenshotStore {
  def config: S3ImageConfig = ???
  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String] = None
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String] = None
  def updatePicture(normalizedUri: NormalizedURI): Future[Option[Seq[Option[PutObjectResult]]]] = Future.successful(None)
}

case class FakeS3ImageStore(val config: S3ImageConfig) extends S3ImageStore {
  def updatePicture(sui: SocialUserInfo, externalId: ExternalId[User]) =
    promise[Seq[PutObjectResult]]().success(Seq()).future
  def getPictureUrl(w: Int, user: User) =
    promise[String]().success(s"http://cloudfront/${user.id.get}_${w}x${w}").future
}
