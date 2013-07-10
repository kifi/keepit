package com.keepit.common.store

import scala.collection.mutable.HashMap

import com.google.inject.{Singleton, Provides}
import com.keepit.common.db.Id
import com.keepit.model.{NormalizedURI, SocialUserInfo}
import com.keepit.search.{InMemoryArticleSearchResultStoreImpl, ArticleSearchResultStore, Article, ArticleStore}
import com.keepit.common.analytics.{FakeMongoS3EventStore, MongoEventStore}
import com.keepit.social.{SocialUserRawInfo, SocialUserRawInfoStore}

trait FakeStoreModule extends StoreModule {

  def configure(): Unit = {
    bind[ArticleStore].toInstance(new HashMap[Id[NormalizedURI], Article] with ArticleStore)
    bind[SocialUserRawInfoStore].toInstance(new HashMap[Id[SocialUserInfo], SocialUserRawInfo] with SocialUserRawInfoStore)
    bind[S3ImageConfig].toInstance(S3ImageConfig("test-bucket", "http://localhost", isLocal = true))
  }

  @Provides @Singleton
  def fakeMongoStore() : MongoEventStore = new FakeMongoS3EventStore()

  @Provides @Singleton
  def articleSearchResultStore(): ArticleSearchResultStore = new InMemoryArticleSearchResultStoreImpl()

}
