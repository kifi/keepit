package com.keepit.cortex.article

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.search.ArticleStore

@ImplementedBy(classOf[StoreBasedArticleProvider])
trait CortexArticleProvider {
  def getArticle(uriId: Id[NormalizedURI]): Option[CortexArticle]
}

@Singleton
class StoreBasedArticleProvider @Inject() (articleStore: ArticleStore) extends CortexArticleProvider {
  def getArticle(uriId: Id[NormalizedURI]): Option[CortexArticle] = articleStore.syncGet(uriId).map { BasicCortexArticle.fromArticle(_) }
}
