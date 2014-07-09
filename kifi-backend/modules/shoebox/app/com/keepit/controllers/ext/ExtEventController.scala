package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.analytics._
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db.{ExternalId, State, Id}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.db.slick.{Database}
import com.keepit.common.akka.SafeFuture
import com.keepit.heimdal.{HeimdalServiceClient, HeimdalContextBuilder, UserEvent, EventType}

import scala.concurrent.future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.json._
import play.api.mvc.Action

import java.math.BigDecimal

class ExtEventController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  userRepo: UserRepo,
  heimdal: HeimdalServiceClient,
  eventHelper: EventHelper,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def logEvent = Action { request =>
    SafeFuture{
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val o = (req \ "event").asInstanceOf[JsObject]

      implicit val experimentFormat = State.format[ExperimentType]
      val eventTime = clock.now.minusMillis((o \ "msAgo").asOpt[Int].getOrElse(0))
      val eventFamily = EventFamilies((o \ "eventFamily").as[String])
      val eventName = (o \ "eventName").as[String]
      val installId = (o \ "installId").asOpt[String].getOrElse("NA")
      val metaData = (o \ "metaData").asOpt[JsObject].getOrElse(Json.obj())
      val prevEvents = (o \ "prevEvents").asOpt[Seq[String]].getOrElse(Seq.empty).map(ExternalId[Event])
      val experiments = (o \ "experiments").as[Seq[ExperimentType]].toSet
      val user = db.readOnlyMaster { implicit s => userRepo.get(userId) }
      val event = Events.userEvent(eventFamily, eventName, user, experiments, installId, metaData, prevEvents, eventTime)
      log.debug(s"Created new event: $event")

      val contextBuilder = new HeimdalContextBuilder()
      contextBuilder += ("experiments", experiments.map(_.toString).toSeq)
      metaData.fields.foreach{
        case (key, jsonValue) => {
          val jsonString = jsonValue match {
            case JsString(s) => s
            case json => Json.stringify(json)
          }
          val value = try {
            val parsedValue = jsonString.toBoolean
            contextBuilder += (key,parsedValue)
          } catch {
            case _: Throwable =>
              try {
                val parsedValue = new BigDecimal(jsonString).doubleValue
                contextBuilder += (key,parsedValue)
              } catch {
                case _: Throwable => contextBuilder += (key,jsonString)
              }
          }
        }
      }
      eventHelper.newEvent(event) // Forwards to listeners
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, EventType(s"old_${eventFamily}_${eventName}"))) // forwards to Heimdal
    }
    Ok("")
  }

}
