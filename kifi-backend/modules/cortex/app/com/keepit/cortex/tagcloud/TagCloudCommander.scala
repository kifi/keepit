package com.keepit.cortex.tagcloud

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.queue.messages.SuggestedSearchTermsWithLibraryId
import com.keepit.model.{ Library, NormalizedURI }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.Article
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

case class LibraryCorpus(articles: Map[Id[NormalizedURI], Set[Article]])

@Singleton
class TagCloudCommander @Inject() (
    rover: RoverServiceClient,
    shoebox: ShoeboxServiceClient) extends Logging {

  def generateTagCloud(libId: Id[Library], experiment: Boolean = false): Future[SuggestedSearchTermsWithLibraryId] = {
    val corpusFuture = for {
      ids <- shoebox.getLibraryURIs(libId)
      articles <- rover.getBestArticlesByUris(ids.toSet)
    } yield LibraryCorpus(articles)

    corpusFuture.map { corpus =>
      log.info(s"library ${libId} corpus retrieved. computing tag clouds")
      val terms = TagCloudGenerator.generate(corpus, experiment)
      SuggestedSearchTermsWithLibraryId(libId, terms)
    }

  }
}
