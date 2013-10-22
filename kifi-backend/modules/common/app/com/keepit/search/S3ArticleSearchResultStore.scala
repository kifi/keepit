package com.keepit.search

import com.amazonaws.services.s3._
import com.keepit.common.store._
import play.api.libs.json.Format
import com.keepit.serializer.ArticleSearchResultSerializer
import com.keepit.common.db.ExternalId


trait ArticleSearchResultStore extends ObjectStore[ExternalId[ArticleSearchResult], ArticleSearchResult] {
  def getSearchId(articleSearchResult: ArticleSearchResult): ExternalId[ArticleSearchResult] =
    articleSearchResult.last.map(getSearchId) getOrElse articleSearchResult.uuid

  def getSearchId(uuid: ExternalId[ArticleSearchResult]): ExternalId[ArticleSearchResult] =
    get(uuid).map(article => getSearchId(article)) getOrElse uuid
}

class S3ArticleSearchResultStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val formatter: Format[ArticleSearchResult] = new ArticleSearchResultSerializer())
  extends S3JsonStore[ExternalId[ArticleSearchResult], ArticleSearchResult] with ArticleSearchResultStore

class InMemoryArticleSearchResultStoreImpl extends InMemoryObjectStore[ExternalId[ArticleSearchResult], ArticleSearchResult] with ArticleSearchResultStore
