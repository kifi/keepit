package com.keepit.common.store

import com.keepit.search.tracking.{ InMemoryProbablisticLRUStoreImpl, ProbablisticLRUStore }

import com.google.inject.{ Singleton, Provides }
import com.keepit.search._

trait FakeStoreModule extends StoreModule {

  def configure(): Unit = {
    bind[S3ImageConfig].toInstance(S3ImageConfig("test-bucket", "http://localhost", isLocal = true))
  }

  @Provides @Singleton
  def articleSearchResultStore(): ArticleSearchResultStore = new InMemoryArticleSearchResultStoreImpl()

  @Singleton
  @Provides
  def probablisticLRUStore(): ProbablisticLRUStore = new InMemoryProbablisticLRUStoreImpl()

  @Provides @Singleton
  def roverImageStore(): RoverImageStore = new InMemoryRoverImageStoreImpl()

  @Provides @Singleton
  def organizationAvatarStore(): OrganizationAvatarStore = new InMemoryOrganizationAvatarStoreImpl()
}
