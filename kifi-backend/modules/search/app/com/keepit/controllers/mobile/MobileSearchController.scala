package com.keepit.controllers.mobile

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.ExperimentType.NO_SEARCH_EXPERIMENTS
import com.keepit.search.DecoratedResult
import com.keepit.search.IdFilterCompressor
import com.keepit.search.KifiSearchResult
import com.keepit.search.ResultUtil
import com.keepit.search.SearchCommander

class MobileSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  searchCommander: SearchCommander
) extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

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
    coll: Option[String] = None
  ) = AuthenticatedJsonAction { request =>

    val userId = request.userId
    val acceptLangs : Seq[String] = request.request.acceptLanguages.map(_.code)
    val noSearchExperiments : Boolean = request.experiments.contains(NO_SEARCH_EXPERIMENTS)

    val decoratedResult = searchCommander.search(userId, acceptLangs, noSearchExperiments, query, filter, maxHits, lastUUIDStr, context, predefinedConfig = None, start, end, tz, coll)

    Ok(toKifiSearchResultV1(decoratedResult)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  def warmUp() = AuthenticatedJsonAction { request =>
    SafeFuture {
      searchCommander.warmUp(request.userId)
    }
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
}

