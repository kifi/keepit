package com.keepit.common.store

import com.google.inject.{Singleton, Provides}
import com.keepit.common.analytics.reports.{InMemoryReportStoreImpl, ReportStore}
import com.keepit.model.NormalizedURI
import scala.concurrent.Future
import com.amazonaws.services.s3.model.PutObjectResult

case class ShoeboxFakeStoreModule() extends FakeStoreModule {

  @Provides @Singleton
  def reportStore(): ReportStore = new InMemoryReportStoreImpl()

  @Provides @Singleton
  def s3ScreenshotStore = FakeS3ScreenshotStore()

}

case class FakeS3ScreenshotStore() extends S3ScreenshotStore {
  def config: S3ImageConfig = ???
  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String] = None
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String] = None
  def updatePicture(normalizedUri: NormalizedURI): Future[Option[Seq[Option[PutObjectResult]]]] = Future.successful(None)
}