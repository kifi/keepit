package com.keepit.search

import com.amazonaws.services.s3._
import com.keepit.common.store._
import play.api.libs.json.Format
import com.keepit.serializer.ArticleSearchResultSerializer
import com.keepit.common.db.ExternalId


trait ArticleSearchResultStore extends ObjectStore[ExternalId[ArticleSearchResult], ArticleSearchResult] {
  def getSearchSession(articleSearchResult: ArticleSearchResult): ExternalId[ArticleSearchResult] =
    articleSearchResult.last.map(getSearchSession) getOrElse articleSearchResult.uuid

  def getSearchSession(uuid: ExternalId[ArticleSearchResult]): ExternalId[ArticleSearchResult] =
    get(uuid).map(article => getSearchSession(article)) getOrElse uuid
}

class S3ArticleSearchResultStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val formatter: Format[ArticleSearchResult] = new ArticleSearchResultSerializer())
  extends S3JsonStore[ExternalId[ArticleSearchResult], ArticleSearchResult] with ArticleSearchResultStore

class InMemoryArticleSearchResultStoreImpl extends InMemoryObjectStore[ExternalId[ArticleSearchResult], ArticleSearchResult] with ArticleSearchResultStore
