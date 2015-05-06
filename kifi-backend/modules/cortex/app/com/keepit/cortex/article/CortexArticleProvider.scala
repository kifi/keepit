package com.keepit.cortex.article

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.rover.RoverServiceClient
import com.keepit.search.ArticleStore

import scala.concurrent.Await
import scala.concurrent.duration._

@ImplementedBy(classOf[RoverArticleProvider])
trait CortexArticleProvider {
  def getArticle(uriId: Id[NormalizedURI]): Option[CortexArticle]
}

@Singleton
class StoreBasedArticleProvider @Inject() (articleStore: ArticleStore) extends CortexArticleProvider {
  def getArticle(uriId: Id[NormalizedURI]): Option[CortexArticle] = articleStore.syncGet(uriId).map { BasicCortexArticle.fromArticle(_) }
}

@Singleton
class RoverArticleProvider @Inject() (rover: RoverServiceClient) extends CortexArticleProvider {
  def getArticle(uriId: Id[NormalizedURI]): Option[CortexArticle] = {
    val res = Await.result(rover.getBestArticlesByUris(Set(uriId)), 5 seconds)
    res.get(uriId) match {
      case Some(articles) if articles.nonEmpty => Some(BasicCortexArticle.fromRoverArticles(articles))
      case _ => None
    }
  }
}
