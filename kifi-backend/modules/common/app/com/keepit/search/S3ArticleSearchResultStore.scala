package com.keepit.search

import com.amazonaws.services.s3._
import com.keepit.common.store._
import play.api.libs.json.Format
import com.keepit.serializer.ArticleSearchResultSerializer
import com.keepit.common.db.ExternalId
import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.store.S3Bucket

trait ArticleSearchResultStore extends ObjectStore[ExternalId[ArticleSearchResult], ArticleSearchResult] {
  def getSearchId(articleSearchResult: ArticleSearchResult): ExternalId[ArticleSearchResult] =
    articleSearchResult.last.map(getSearchId) getOrElse articleSearchResult.uuid

  def getSearchId(uuid: ExternalId[ArticleSearchResult]): ExternalId[ArticleSearchResult] =
    get(uuid).map(article => getSearchId(article)) getOrElse uuid
}

class S3ArticleSearchResultStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, searchIdCache: SearchIdCache, val formatter: Format[ArticleSearchResult] = new ArticleSearchResultSerializer())
  extends S3JsonStore[ExternalId[ArticleSearchResult], ArticleSearchResult] with ArticleSearchResultStore {

  override def getSearchId(uuid: ExternalId[ArticleSearchResult]): ExternalId[ArticleSearchResult] = searchIdCache.getOrElse(SearchIdArticleIdKey(uuid)) { super.getSearchId(uuid) }
}

class InMemoryArticleSearchResultStoreImpl extends InMemoryObjectStore[ExternalId[ArticleSearchResult], ArticleSearchResult] with ArticleSearchResultStore

case class SearchIdArticleIdKey(uuid: ExternalId[ArticleSearchResult]) extends Key[ExternalId[ArticleSearchResult]] {
  override val version = 1
  val namespace = "search_id_by_article_id"
  def toKey(): String = uuid.id
}

class SearchIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SearchIdArticleIdKey, ExternalId[ArticleSearchResult]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(ExternalId.format[ArticleSearchResult])