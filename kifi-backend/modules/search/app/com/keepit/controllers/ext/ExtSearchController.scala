package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller.{ SearchServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.engine.result.KifiPlainResult
import com.keepit.search.result.DecoratedResult
import com.keepit.search.result.KifiSearchResult
import com.keepit.search.result.ResultUtil
import com.keepit.search.util.IdFilterCompressor
import com.keepit.search.SearchCommander
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import scala.concurrent.Future

class ExtSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
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

    val plainResultFuture = searchCommander.search2(userId, acceptLangs, request.experiments, query, filter, maxHits, lastUUIDStr, context, predefinedConfig = None, debugOpt)

    val plainResultEnumerator = Enumerator.flatten(plainResultFuture.map(r => Enumerator(toKifiSearchResultV2(r)))(immediate))

    var decorationFutures = Seq.empty[Future[JsObject]]

    // TODO: augmentation
    // decorationFutures += (plainResultFuture.map { ... })

    // TODO: URI Summary
    // if (withUriSummary) decorationFutures += (plainResultFuture.map { ... })

    val decorationEnumerator = reactiveEnumerator(decorationFutures)

    val resultEnumerator = plainResultEnumerator.andThen(decorationEnumerator).andThen(Enumerator.eof)

    Ok.chunked(resultEnumerator).withHeaders("Cache-Control" -> "private, max-age=10")
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

