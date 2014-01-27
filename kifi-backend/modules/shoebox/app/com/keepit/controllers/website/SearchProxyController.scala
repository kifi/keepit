package com.keepit.controllers

import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.search.SearchServiceClient
import com.keepit.model.KifiVersion
import com.keepit.model.ExperimentType.NO_SEARCH_EXPERIMENTS

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

class SearchProxyController @Inject() (actionAuthenticator: ActionAuthenticator, searchServiceClient: SearchServiceClient) extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def search() = JsonAction.authenticatedAsync { request =>
    searchServiceClient.search(request.userId, request.experiments.contains(NO_SEARCH_EXPERIMENTS), request.request.acceptLanguages.map(_.code), request.rawQueryString).map{ res =>
      Ok(res).withHeaders("Content-Type" -> "application/json")
    }
  }
}
