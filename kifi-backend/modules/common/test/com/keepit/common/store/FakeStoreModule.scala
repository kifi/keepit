package com.keepit.common.store

import scala.collection.mutable.HashMap

import com.amazonaws.services.s3.model._
import scala.concurrent.Future
import com.google.inject.{Singleton, Provides}
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.learning.topicmodel._
import com.keepit.common.db._
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
  def probablisticLRUStore(): ProbablisticLRUStore = new HashMap[FullFilterChunkId, Array[Int]] with ProbablisticLRUStore{}

  @Singleton
  @Provides
  def articleStore(): ArticleStore = new HashMap[Id[NormalizedURI], Article] with ArticleStore {
  }

  @Singleton
  @Provides
  def wordTopicStore(): WordTopicStore = new HashMap[String, String] with WordTopicStore {
  }

  @Singleton
  @Provides
  def wordTopicBlobStore(): WordTopicBlobStore = new HashMap[String, Array[Double]] with WordTopicBlobStore {
  }

  @Singleton
  @Provides
  def wordStore(): WordStore = new HashMap[String, Array[String]] with WordStore {
  }

  @Singleton
  @Provides
  def topicWordsStore(): TopicWordsStore = new HashMap[String, String] with TopicWordsStore {
  }
}
