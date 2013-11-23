package com.keepit.controllers

import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.search.SearchServiceClient
import com.keepit.model.KifiVersion
import com.keepit.model.ExperimentType.NO_SEARCH_EXPERIMENTS

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

class SearchProxyController @Inject() (actionAuthenticator: ActionAuthenticator, searchServiceClient: SearchServiceClient) extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

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

    Async(
      searchServiceClient.search(request.userId, request.experiments.contains(NO_SEARCH_EXPERIMENTS), request.request.acceptLanguages.map(_.code), query, filter, maxHits, lastUUIDStr, context, kifiVersion, start, end, tz, coll).map{ res =>
        Ok(res).withHeaders("Content-Type" -> "application/json")
      }
    )

  }
}
