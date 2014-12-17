package com.keepit.common.store

import com.keepit.search.tracking.{ FullFilterChunkId, ProbablisticLRUStore }

import scala.collection.mutable.HashMap
import com.google.inject.{ Singleton, Provides }
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.search._
import com.keepit.social._

trait FakeStoreModule extends StoreModule {

  def configure(): Unit = {
    bind[SocialUserRawInfoStore].toInstance(new HashMap[Id[SocialUserInfo], SocialUserRawInfo] with SocialUserRawInfoStore)
    bind[S3ImageConfig].toInstance(S3ImageConfig("test-bucket", "http://localhost", isLocal = true))
  }

  @Provides @Singleton
  def articleSearchResultStore(): ArticleSearchResultStore = new InMemoryArticleSearchResultStoreImpl()

  @Singleton
  @Provides
  def probablisticLRUStore(): ProbablisticLRUStore = new HashMap[FullFilterChunkId, Array[Int]] with ProbablisticLRUStore {}

  @Singleton
  @Provides
  def articleStore(): ArticleStore = new HashMap[Id[NormalizedURI], Article] with ArticleStore {
  }
}
