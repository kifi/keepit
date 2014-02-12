package com.keepit.controllers.ext

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.Action
import play.api.libs.json._
import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.ExperimentType.NO_SEARCH_EXPERIMENTS
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.result.DecoratedResult
import com.keepit.search.IdFilterCompressor
import com.keepit.search.result.KifiSearchResult
import com.keepit.search.result.ResultUtil
import com.keepit.search.SearchCommander

class ExtSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  searchCommander: SearchCommander
) extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

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
    debug: Option[String] = None) = JsonAction.authenticated { request =>

    val userId = request.userId
    val acceptLangs : Seq[String] = request.request.acceptLanguages.map(_.code)

    val debugOpt = debug.flatMap{ v => if (request.experiments.contains(ADMIN)) Some(v) else None } // debug is only for admin

    val decoratedResult = searchCommander.search(userId, acceptLangs, request.experiments, query, filter, maxHits, lastUUIDStr, context, predefinedConfig = None, start, end, tz, coll, debug)

    Ok(toKifiSearchResultV1(decoratedResult)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  //external (from the extension/website)
  def warmUp() = JsonAction.authenticated { request =>
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

  def internalSearch(
    userId: Long,
    exp: String,
    acceptLangs: String,
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
  ) = Action { request =>

    val uid = Id[User](userId)

    val experiments = exp.split(",").map(ExperimentType(_)).toSet
    val decoratedResult = searchCommander.search(uid, acceptLangs.split(","), experiments , query, filter, maxHits, lastUUIDStr, context, predefinedConfig = None, start, end, tz, coll, None)

    Ok(toKifiSearchResultV1(decoratedResult)).withHeaders("Cache-Control" -> "private, max-age=10")
  }
}

