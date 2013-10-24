package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.time._

import play.api.http.ContentTypes
import play.api.libs.json._

class ExtErrorReportController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  airbrake: AirbrakeNotifier)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def addErrorReport = JsonToJsonAction(true) (authenticatedAction = { request =>
    val json = request.body
    val message = (json \ "message").as[String]
    val (inst, userId, exps) = (request.kifiInstallationId.getOrElse(""), request.userId, request.experiments.mkString(","))
    val errorReport = airbrake.notify(AirbrakeError(
      message = Some(s"""Extension error "$message": on installation id $inst, user $userId, experiments [$exps]""")
    ))
    Ok(JsObject(Seq("errorId" -> JsString(errorReport.id.id))))
  }, unauthenticatedAction = { request =>
    val json = request.body
    val message = (json \ "message").as[String]
    val errorReport = airbrake.notify(AirbrakeError(
      message = Some(s"error of unauthenticated user in extension: $message")
    ))
    Ok(JsObject(Seq("errorId" -> JsString(errorReport.id.id)))).as(ContentTypes.JSON)
  })
}
