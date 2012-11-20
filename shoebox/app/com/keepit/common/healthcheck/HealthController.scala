package com.keepit.common.healthcheck

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import play.api.libs.json.JsArray
import play.api.http.ContentTypes
import com.keepit.controllers.CommonActions._
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.serializer.BookmarkSerializer
import com.keepit.serializer.{URIPersonalSearchResultSerializer => BPSRS}
import com.keepit.common.db.ExternalId
import java.util.concurrent.TimeUnit
import java.sql.Connection
import play.api.http.ContentTypes
import play.api.libs.json.JsString
import securesocial.core._
import com.keepit.common.controller.FortyTwoServices._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.controller.FortyTwoServices

object HealthController extends Controller with Logging with SecureSocial {

  def serviceView = SecuredAction(false) { implicit request =>
    Ok(views.html.serverInfo(currentService, currentVersion, compilationTime.toStandardTimeString, started.toStandardTimeString))
  }
}
