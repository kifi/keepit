package com.keepit.controllers.ext

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.http.ContentTypes
import play.api.libs.json._

import com.keepit.model._
import java.sql.Connection
import com.keepit.common.time._
import com.keepit.common.healthcheck._
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}

import com.google.inject.{Inject, Singleton}

@Singleton
class ExtErrorReportController @Inject() (
  actionAuthenticator: ActionAuthenticator,
    healthcheck: HealthcheckPlugin)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def addErrorReport = AuthenticatedJsonToJsonAction { request =>
    val json = request.body
    val message = (json \ "message").as[String]
    val errorReport = healthcheck.addError(
      HealthcheckError(error = None,
        callType = Healthcheck.EXTENSION,
        errorMessage = Some(s"error on user ${request.userId} extension installation id ${request.kifiInstallationId}" +
          s" using experiments [${request.experiments.mkString(",")}]" +
          s": $message")
      )
    )
    Ok(JsObject(Seq("error-id" -> JsString(errorReport.id.id))))
  }

  def addUnauthenticatedErrorReport = Action(parse.tolerantJson) { request =>
    val json = request.body
    val message = (json \ "message").as[String]
    val errorReport = healthcheck.addError(HealthcheckError(error = None,
      callType = Healthcheck.EXTENSION,
      errorMessage = Some(s"error of unauthenticated user: $message")
    ))
    Ok(JsObject(Seq("error-id" -> JsString(errorReport.id.id)))).as(ContentTypes.JSON)
  }
}
