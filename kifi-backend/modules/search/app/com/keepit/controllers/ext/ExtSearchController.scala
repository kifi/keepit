package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller.{ SearchServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.engine.result.KifiPlainResult
import com.keepit.search.result.DecoratedResult
import com.keepit.search.result.KifiSearchResult
import com.keepit.search.result.ResultUtil
import com.keepit.search.util.IdFilterCompressor
import com.keepit.search.SearchCommander
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import scala.concurrent.Future

class ExtSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    shoeboxClient: ShoeboxServiceClient,
    searchCommander: SearchCommander) extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

  def search(
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    kifiVersion: Option[KifiVersion] = None,
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None,
    debug: Option[String] = None,
    withUriSummary: Boolean = false) = JsonAction.authenticated { request =>

    val userId = request.userId
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)

    val debugOpt = if (debug.isDefined && request.experiments.contains(ADMIN)) debug else None // debug is only for admin

    val decoratedResult = searchCommander.search(userId, acceptLangs, request.experiments, query, filter, maxHits, lastUUIDStr, context, predefinedConfig = None, debugOpt, withUriSummary)

    Ok(toKifiSearchResultV1(decoratedResult)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  def search2(
    query: String,
    filter: Option[String],
    library: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    kifiVersion: Option[KifiVersion] = None,
    debug: Option[String] = None,
    withUriSummary: Boolean = false) = JsonAction.authenticated { request =>

    val userId = request.userId
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)

    val debugOpt = if (debug.isDefined && request.experiments.contains(ADMIN)) debug else None // debug is only for admin

    val plainResultFuture = searchCommander.search2(userId, acceptLangs, request.experiments, query, filter, library, maxHits, lastUUIDStr, context, predefinedConfig = None, debugOpt)

    val plainResultEnumerator = Enumerator.flatten(plainResultFuture.map(r => Enumerator(toKifiSearchResultV2(r).toString))(immediate))

    var decorationFutures: List[Future[String]] = Nil

    // TODO: augmentation
    // decorationFutures = augmentationFuture(plainResultFuture) :: decorationFutures

    if (withUriSummary) {
      decorationFutures = uriSummaryInfoFuture(plainResultFuture) :: decorationFutures
    }

    val decorationEnumerator = reactiveEnumerator(decorationFutures)

    val resultEnumerator = Enumerator("[").andThen(plainResultEnumerator).andThen(decorationEnumerator).andThen(Enumerator("]")).andThen(Enumerator.eof)

    Ok.chunked(resultEnumerator).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  private def augmentationFuture(plainResultFuture: Future[KifiPlainResult]): Future[String] = ??? // TODO: augmentation

  private def uriSummaryInfoFuture(plainResultFuture: Future[KifiPlainResult]): Future[String] = {
    plainResultFuture.flatMap { r =>
      val uriIds = r.hits.map(h => Id[NormalizedURI](h.id))
      shoeboxClient.getUriSummaries(uriIds).map { uriSummaries =>
        KifiSearchResult.uriSummaryInfoV2(uriIds.map { uriId => uriSummaries.get(uriId) }).toString
      }
    }
  }

  @inline
  private def reactiveEnumerator[T](futureSeq: Seq[Future[T]]) = {
    // Returns successful results of Futures in the order they are completed, reactively
    Enumerator.interleave(futureSeq.map { future =>
      Enumerator.flatten(future.map(r => Enumerator(r))(immediate))
    })
  }

  //external (from the extension)
  def warmUp() = JsonAction.authenticated { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }

  private def toKifiSearchResultV1(decoratedResult: DecoratedResult): JsObject = {
    KifiSearchResult.v1(
      decoratedResult.uuid,
      decoratedResult.query,
      ResultUtil.toKifiSearchHits(decoratedResult.hits),
      decoratedResult.myTotal,
      decoratedResult.friendsTotal,
      decoratedResult.othersTotal,
      decoratedResult.mayHaveMoreHits,
      decoratedResult.show,
      decoratedResult.searchExperimentId,
      IdFilterCompressor.fromSetToBase64(decoratedResult.idFilter),
      Nil,
      decoratedResult.experts).json
  }

  private def toKifiSearchResultV2(KifiPlainResult: KifiPlainResult): JsObject = {
    KifiSearchResult.v2(
      KifiPlainResult.uuid,
      KifiPlainResult.query,
      KifiPlainResult.hits,
      KifiPlainResult.myTotal,
      KifiPlainResult.friendsTotal,
      KifiPlainResult.mayHaveMoreHits,
      KifiPlainResult.show,
      KifiPlainResult.searchExperimentId,
      IdFilterCompressor.fromSetToBase64(KifiPlainResult.idFilter)).json
  }
}

