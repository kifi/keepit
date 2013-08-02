package com.keepit.controllers.ext

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.future
import scala.concurrent.Future
import scala.util.Try
import com.google.inject.Inject
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.controller.{SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.performance._
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.search._
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{HealthcheckPlugin, HealthcheckError}
import com.keepit.common.healthcheck.Healthcheck.SEARCH
import com.keepit.search.comment.CommentSearcher
import com.keepit.search.comment.CommentSearchResult
import com.keepit.search.comment.CommentHit
import com.keepit.search.comment.CommentIndexer
import com.keepit.search.comment.CommentQueryParser
import com.keepit.search.query.parser.QueryParser
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.serializer.CommentSearchResultSerializer.resSerializer
import play.api.libs.json.Json
import com.newrelic.api.agent.NewRelic
import com.newrelic.api.agent.Trace
import play.modules.statsd.api.Statsd
import com.keepit.social.BasicUser

class ExtCommentSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  commentIndexer: CommentIndexer,
  healthcheckPlugin: HealthcheckPlugin)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging{

  @Trace
  def search(query: String,
             maxHits: Int,
             context: Option[String]) = AuthenticatedJsonAction { request =>

    val userId = request.userId
    log.info(s"""User ${userId} searched ${query.length} characters""")

    val searcher = new CommentSearcher(commentIndexer.getSearcher)

    val searchRes = if (maxHits > 0) {
      val parser = getParser(Lang("en"))
      val searchRes = parser.parse(query).map{ parsedQuery =>
        val idFilter = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
        searcher.search(userId, parsedQuery, maxHits, idFilter)
      }
      searchRes.getOrElse(new CommentSearchResult(Array.empty[CommentHit], context.getOrElse("")))
    } else {
      log.warn("maxHits is zero")
      new CommentSearchResult(Array.empty[CommentHit], context.getOrElse(""))
    }

    val commentStore = commentIndexer.commentStore
    searchRes.hits.foreach{ h =>
      val rec = commentStore.getCommentRecord(h.id).getOrElse(throw new Exception(s"missing comment record: comment id = ${h.id}"))
      h.externalId = rec.externalId
    }

     Ok(Json.toJson(searchRes)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  private def getParser(lang: Lang): QueryParser = {
    new CommentQueryParser(
      DefaultAnalyzer.forParsing(lang),
      DefaultAnalyzer.forParsingWithStemmer(lang)
    )
  }
}
