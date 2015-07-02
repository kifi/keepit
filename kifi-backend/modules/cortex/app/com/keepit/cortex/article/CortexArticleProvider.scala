package com.keepit.cortex.article

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.rover.RoverServiceClient

import scala.concurrent.Await
import scala.concurrent.duration._

@ImplementedBy(classOf[RoverArticleProvider])
trait CortexArticleProvider {
  def getArticle(uriId: Id[NormalizedURI]): Option[CortexArticle]
}

@Singleton
class RoverArticleProvider @Inject() (rover: RoverServiceClient) extends CortexArticleProvider {
  def getArticle(uriId: Id[NormalizedURI]): Option[CortexArticle] = {
    val res = Await.result(rover.getBestArticlesByUris(Set(uriId)), 30 seconds)
    res.get(uriId) match {
      case Some(articles) if articles.nonEmpty => Some(BasicCortexArticle.fromRoverArticles(articles))
      case _ => None
    }
  }
}
