package com.keepit.controllers.ext

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.healthcheck._
import com.keepit.common.time._

import play.api.http.ContentTypes
import play.api.libs.json._

@Singleton
class ExtErrorReportController @Inject() (
  actionAuthenticator: ActionAuthenticator,
    healthcheck: HealthcheckPlugin)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  val enableExtensionErrorReporting = false

  def addErrorReport = JsonToJsonAction(true) (authenticatedAction = { request =>
    if (enableExtensionErrorReporting) {
      val json = request.body
      val message = (json \ "message").as[String]
      val (inst, userId, exps) =
        (request.kifiInstallationId.getOrElse(""), request.userId, request.experiments.mkString(","))
      val errorReport = healthcheck.addError(
        HealthcheckError(error = None,
          callType = Healthcheck.EXTENSION,
          errorMessage = Some(ErrorMessage(
            s"""Extension error "$message" """,
            Some(s"on installation id $inst, user $userId, experiments [$exps]")
          ))
        )
      )
      Ok(JsObject(Seq("errorId" -> JsString(errorReport.id.id))))
    } else Ok(Json.obj())
  }, unauthenticatedAction = { request =>
    if (enableExtensionErrorReporting) {
      val json = request.body
      val message = (json \ "message").as[String]
      val errorReport = healthcheck.addError(HealthcheckError(error = None,
        callType = Healthcheck.EXTENSION,
        errorMessage = Some(s"error of unauthenticated user: $message")
      ))
      Ok(JsObject(Seq("errorId" -> JsString(errorReport.id.id)))).as(ContentTypes.JSON)
    } else Ok(Json.obj())
  })
}
