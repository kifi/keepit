package com.keepit.common.store

import com.google.inject.{Singleton, Provides}
import com.keepit.model.{User, SocialUserInfo, NormalizedURI}
import scala.concurrent._
import com.amazonaws.services.s3.model.PutObjectResult
import com.keepit.common.db.{Id, ExternalId}
import scala.util.{Success, Try}
import java.io.File

case class ShoeboxFakeStoreModule() extends FakeStoreModule {

  @Provides @Singleton
  def s3ScreenshotStore: S3ScreenshotStore = FakeS3ScreenshotStore()

  @Provides @Singleton
  def s3ImageStore(s3ImageConfig: S3ImageConfig): S3ImageStore = FakeS3ImageStore(s3ImageConfig)
}

