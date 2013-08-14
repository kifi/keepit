package com.keepit.controllers.ext

import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.time._

import play.api.Play.current
import play.api.libs.json.{JsBoolean, JsObject, Json}

import com.google.inject.Inject
import com.keepit.controllers.core.KeeperInfoLoader
import com.keepit.normalizer.{NormalizationService, NormalizationCandidate}

class ExtPageController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  keeperInfoLoader: KeeperInfoLoader,
  normalizationService: NormalizationService,
  normalizedUriRepo: NormalizedURIRepo)
  extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getPageDetails() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    val nUri =  db.readOnly { implicit session => normalizationService.normalize(url) }

    Ok(Json.obj(
      "normalized" -> nUri,
      "uri_1" -> keeperInfoLoader.load1(request.user.id.get, nUri),
      "uri_2" -> keeperInfoLoader.load2(request.user.id.get, nUri)
    ))
  }

  def recordCanonicalUrl() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    db.readWrite { implicit session =>
      normalizedUriRepo.internByUri(url, NormalizationCandidate(request.body.as[JsObject]): _*)
    }
    Ok(JsBoolean(true))
  }

}
