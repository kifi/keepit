package com.keepit.controllers.ext

import com.keepit.classify.{Domain, DomainRepo, DomainStates}
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.net.{URINormalizer, URI}
import com.keepit.model._
import com.keepit.common.time._

import play.api.Play.current
import play.api.libs.json.{JsObject, Json}

import com.google.inject.Inject
import com.keepit.controllers.core.KeeperInfoLoader

class ExtPageController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  keeperInfoLoader: KeeperInfoLoader)
  extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getPageDetails(url: String) = AuthenticatedJsonToJsonAction { request =>
    val nUri = URINormalizer.normalize(url)

    Ok(Json.obj(
      "normalized" -> nUri,
      "uri_1" -> keeperInfoLoader.load1(request.user.id.get, nUri),
      "uri_2" -> keeperInfoLoader.load2(request.user.id.get, nUri)
    ))
  }

}
