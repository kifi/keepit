package com.keepit.controllers.ext

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.Action
import play.api.mvc.Request
import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{AuthenticatedRequest, SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.model.ExperimentType.NO_SEARCH_EXPERIMENTS

class ExtSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  searchCommander: SearchCommander
) extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

  def internalSearch(
    userId: Long,
    noSearchExperiments: Boolean,
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
    coll: Option[String] = None) = Action { request =>

    val res = searchCommander.search(Id[User](userId), acceptLangs.split(","), noSearchExperiments, query, filter, maxHits, lastUUIDStr, context, start, end, tz, coll)

    Ok(res).withHeaders("Cache-Control" -> "private, max-age=10")
  }

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
    coll: Option[String] = None) = AuthenticatedJsonAction { request =>

    val userId = request.userId
    val acceptLangs : Seq[String] = request.request.acceptLanguages.map(_.code)
    val noSearchExperiments : Boolean = request.experiments.contains(NO_SEARCH_EXPERIMENTS)

    val res = searchCommander.search(userId, acceptLangs, noSearchExperiments, query, filter, maxHits, lastUUIDStr, context, start, end, tz, coll)

    Ok(res).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  //external (from the extension/website)
  def warmUp() = AuthenticatedJsonAction { request =>
    SafeFuture {
      searchCommander.warmUp(request.userId)
    }
    Ok
  }
}
