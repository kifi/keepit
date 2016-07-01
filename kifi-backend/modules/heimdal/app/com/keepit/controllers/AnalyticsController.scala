package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.heimdal._
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsNumber, JsObject, Json }
import play.api.mvc.Action
import views.html

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

class AnalyticsController @Inject() (mixpanelClient: MixpanelClient)
    extends HeimdalServiceController {

  def deleteUser(userId: Id[User]) = Action.async { request =>
    SafeFuture {
      mixpanelClient.delete(userId)
      // TODO amplitude?
      Ok
    }
  }

  def incrementUserProperties(userId: Id[User]) = Action.async { request =>
    val increments = request.body.asJson.get.as[JsObject].value.mapValues(_.as[Double]).toMap
    SafeFuture {
      mixpanelClient.incrementUserProperties(userId, increments)
      Ok
    }
  }

  def setUserProperties(userId: Id[User]) = Action.async { request =>
    val properties = Json.fromJson[HeimdalContext](request.body.asJson.get).get
    SafeFuture {
      mixpanelClient.setUserProperties(userId, properties)
      Ok
    }
  }

  def setUserAlias(userId: Id[User], externalId: ExternalId[User]) = Action.async { request =>
    SafeFuture {
      mixpanelClient.alias(userId, externalId)
      Ok
    }
  }
}
