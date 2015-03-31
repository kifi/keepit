package com.keepit.search

import com.amazonaws.services.s3._
import com.keepit.common.store._
import com.keepit.common.db.ExternalId
import com.keepit.common.cache._
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.store.S3Bucket

trait ArticleSearchResultStore extends ObjectStore[ExternalId[ArticleSearchResult], ArticleSearchResult] {
  def getInitialSearchId(articleSearchResult: ArticleSearchResult): ExternalId[ArticleSearchResult] =
    articleSearchResult.last.map(getInitialSearchId) getOrElse articleSearchResult.uuid

  def getInitialSearchId(uuid: ExternalId[ArticleSearchResult]): ExternalId[ArticleSearchResult] =
    syncGet(uuid).map(article => getInitialSearchId(article)) getOrElse uuid
}

class S3ArticleSearchResultStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, initialSearchIdCache: InitialSearchIdCache, articleCache: ArticleSearchResultCache)
    extends S3JsonStore[ExternalId[ArticleSearchResult], ArticleSearchResult] with ArticleSearchResultStore {

  val formatter = ArticleSearchResult.format
  override def getInitialSearchId(uuid: ExternalId[ArticleSearchResult]): ExternalId[ArticleSearchResult] = initialSearchIdCache.getOrElse(InitialSearchIdSearchIdKey(uuid)) { super.getInitialSearchId(uuid) }
  override def syncGet(uuid: ExternalId[ArticleSearchResult]): Option[ArticleSearchResult] = articleCache.getOrElseOpt(ArticleSearchResultIdKey(uuid)) { super.syncGet(uuid) }
  override def +=(uuidAndArticle: (ExternalId[ArticleSearchResult], ArticleSearchResult)) = {
    val (uuid, article) = uuidAndArticle
    articleCache.set(ArticleSearchResultIdKey(uuid), article)
    super.+=(uuidAndArticle)
  }
}

class InMemoryArticleSearchResultStoreImpl extends InMemoryObjectStore[ExternalId[ArticleSearchResult], ArticleSearchResult] with ArticleSearchResultStore

case class InitialSearchIdSearchIdKey(uuid: ExternalId[ArticleSearchResult]) extends Key[ExternalId[ArticleSearchResult]] {
  override val version = 1
  val namespace = "initial_search_id_by_search_id"
  def toKey(): String = uuid.id
}

class InitialSearchIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[InitialSearchIdSearchIdKey, ExternalId[ArticleSearchResult]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(ExternalId.format[ArticleSearchResult])

case class ArticleSearchResultIdKey(uuid: ExternalId[ArticleSearchResult]) extends Key[ArticleSearchResult] {
  override val version = 1
  val namespace = "article_search_result_by_id"
  def toKey(): String = uuid.id
}

class ArticleSearchResultCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[ArticleSearchResultIdKey, ArticleSearchResult](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)