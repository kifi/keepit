package com.keepit.rover.store

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.FakeStoreModule

case class RoverFakeStoreModule() extends FakeStoreModule with RoverStoreModule {

  @Provides @Singleton
  def roverArticleStore(amazonS3Client: AmazonS3, accessLog: AccessLog): RoverUnderlyingArticleStore = {
    new InMemoryRoverUnderlyingArticleStoreImpl()
  }
}
