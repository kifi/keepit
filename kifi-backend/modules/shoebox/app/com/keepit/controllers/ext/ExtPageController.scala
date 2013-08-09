package com.keepit.controllers.ext

import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.time._

import play.api.Play.current
import play.api.libs.json.{JsObject, Json}

import com.google.inject.Inject
import com.keepit.controllers.core.KeeperInfoLoader
import com.keepit.normalizer.NormalizationService

class ExtPageController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  keeperInfoLoader: KeeperInfoLoader,
  normalizationService: NormalizationService)
  extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getPageDetails(url: String) = AuthenticatedJsonAction { request =>
    val nUri =  db.readOnly { implicit session => normalizationService.normalize(url) }

    Ok(Json.obj(
      "normalized" -> nUri,
      "uri_1" -> keeperInfoLoader.load1(request.user.id.get, nUri),
      "uri_2" -> keeperInfoLoader.load2(request.user.id.get, nUri)
    ))
  }

}
