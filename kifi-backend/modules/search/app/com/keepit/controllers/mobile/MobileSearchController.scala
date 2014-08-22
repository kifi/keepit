package com.keepit.controllers.mobile

import play.api.libs.json._
import com.google.inject.Inject
import com.keepit.common.controller.{ MobileController, SearchServiceController, ActionAuthenticator }
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search.result.DecoratedResult
import com.keepit.search.result.KifiSearchResult
import com.keepit.search.result.ResultUtil
import com.keepit.search.util.IdFilterCompressor
import com.keepit.search.SearchCommander

class MobileSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    searchCommander: SearchCommander) extends MobileController(actionAuthenticator) with SearchServiceController with Logging {

  def searchV1(
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
    withUriSummary: Boolean = false) = JsonAction.authenticated { request =>

    val userId = request.userId
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)

    val decoratedResult = searchCommander.search(userId, acceptLangs, request.experiments, query, filter, maxHits, lastUUIDStr, context, predefinedConfig = None, None, withUriSummary)

    Ok(toKifiSearchResultV1(decoratedResult)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  def warmUp() = JsonAction.authenticated { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }

  private def toKifiSearchResultV1(decoratedResult: DecoratedResult): JsObject = {
    KifiSearchResult.v1(
      decoratedResult.uuid,
      decoratedResult.query,
      ResultUtil.toSanitizedKifiSearchHits(decoratedResult.hits),
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
}

