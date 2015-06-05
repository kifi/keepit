package com.keepit.rover.tagcloud

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.queue.messages.{ SuggestedSearchTerms, SuggestedSearchTermsWithLibraryId }
import com.keepit.model.{ NormalizedURI, Library }
import com.keepit.rover.article.{ Article, ArticleCommander }
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

case class LibraryCorpus(articles: Map[Id[NormalizedURI], Set[Article]])

@Singleton
class TagCloudCommander @Inject() (
    articleCommander: ArticleCommander,
    shoebox: ShoeboxServiceClient) {

  def generateTagCloud(libId: Id[Library]): Future[SuggestedSearchTermsWithLibraryId] = {
    val corpusFuture = for {
      ids <- shoebox.getLibraryURIs(libId)
      articles <- articleCommander.getBestArticlesByUris(ids.toSet)
    } yield LibraryCorpus(articles)

    corpusFuture.map { corpus =>
      val terms = TagCloudGenerator.generate(corpus)
      SuggestedSearchTermsWithLibraryId(libId, terms)
    }

  }
}
