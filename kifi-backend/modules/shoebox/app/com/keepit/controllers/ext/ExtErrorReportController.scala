package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.time._
import com.keepit.heimdal.{HeimdalServiceClient, EventContextBuilderFactory, UserEvent, EventType}

import play.api.http.ContentTypes
import play.api.libs.json._

class ExtErrorReportController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  userEventContextBuilder: EventContextBuilderFactory,
  heimdal: HeimdalServiceClient)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def addErrorReport = JsonToJsonAction(true) (authenticatedAction = { request =>
    val json = request.body
    val message = (json \ "message").as[String]
    val (inst, userId, exps) = (request.kifiInstallationId.getOrElse(""), request.userId, request.experiments.mkString(","))
    log.error(s"""Extension error "$message": on installation id $inst, user $userId, experiments [$exps]""")
    val contextBuilder = userEventContextBuilder(Some(request))
    contextBuilder += ("message", message)
    contextBuilder += ("authenticated", true)
    heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, EventType("ext_error")))
    Ok(JsObject(Seq("res" -> JsString("ok")))).as(ContentTypes.JSON)
  }, unauthenticatedAction = { request =>
    val json = request.body
    val message = (json \ "message").as[String]
    log.error(s"error of unauthenticated user in extension: $message")
    val contextBuilder = userEventContextBuilder(Some(request))
    contextBuilder += ("message", message)
    contextBuilder += ("authenticated", false)
    heimdal.trackEvent(UserEvent(-1, contextBuilder.build, EventType("ext_error")))
    Ok(JsObject(Seq("res" -> JsString("ok")))).as(ContentTypes.JSON)
  })
}
