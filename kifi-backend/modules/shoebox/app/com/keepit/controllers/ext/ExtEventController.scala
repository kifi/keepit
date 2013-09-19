package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.analytics._
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db.{ExternalId, State, Id}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.db.slick.{Database}

import scala.concurrent.future
import scala.concurrent.ExecutionContext.Implicits.global

import play.api.libs.json._
import play.api.mvc.Action

class ExtEventController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  eventPersister: EventPersister,
  db: Database,
  userRepo: UserRepo,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def logEvent = Action { request =>
    future{
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val o = (req \ "event").asInstanceOf[JsObject]

      implicit val experimentFormat = State.format[ExperimentType]
      val eventTime = clock.now.minusMillis((o \ "msAgo").asOpt[Int].getOrElse(0))
      val eventFamily = EventFamilies((o \ "eventFamily").as[String])
      val eventName = (o \ "eventName").as[String]
      val installId = (o \ "installId").as[String]
      val metaData = (o \ "metaData").asOpt[JsObject].getOrElse(Json.obj())
      val prevEvents = (o \ "prevEvents").asOpt[Seq[String]].getOrElse(Seq.empty).map(ExternalId[Event])
      val experiments = (o \ "experiments").as[Seq[State[ExperimentType]]].toSet
      val user = db.readOnly { implicit s => userRepo.get(userId) }
      val event = Events.userEvent(eventFamily, eventName, user, experiments, installId, metaData, prevEvents, eventTime)
      log.debug(s"Created new event: $event")
      eventPersister.persist(event)
    }
    Ok("")
  }

  def logUserEvents = AuthenticatedJsonToJsonAction { request =>
    val json = request.body
    (json \ "version").as[Int] match {
      case 1 => createEventsFromPayload(json, request.user, request.experiments)
      case i => throw new Exception("Unknown events version: $i")
    }
    Ok(JsObject(Seq("stored" -> JsString("ok"))))
  }

  private[ext] def createEventsFromPayload(params: JsValue, user: User, experiments: Set[State[ExperimentType]]) = {
    val logRecievedTime = currentDateTime

    val events = (params \ "events") match {
      case JsArray(ev) => ev map (  _.as[JsObject] )
      case _: JsValue => throw new Exception()
    }

    val logClientTime = (params \ "time").as[Int]
    val globalInstallId = (params \ "installId").asOpt[String].getOrElse("")

      events map { event =>
        val eventTimeAgo = math.max(logClientTime - (event \ "time").as[Int],0)
        val eventTime = logRecievedTime.minusMillis(eventTimeAgo)

        val eventFamily = EventFamilies((event \ "eventFamily").as[String])
        val eventName = (event \ "eventName").as[String]
        val installId = (event \ "installId").asOpt[String].getOrElse(globalInstallId)
        val metaData = (event \ "metaData").asOpt[JsObject].getOrElse(JsObject(Seq()))
        val prevEvents = ((event \ "prevEvents") match {
          case JsArray(s) =>
            Some(s map { ext =>
              ext match {
                case JsString(id) => Some(ExternalId[Event](id))
                case _: JsValue => None
              }
            } flatten)
          case _: JsValue => None
        }).getOrElse(Seq())
        val newEvent = Events.userEvent(eventFamily, eventName, user, experiments, installId, metaData, prevEvents, eventTime)
        log.debug(s"Created new event: $newEvent")

        eventPersister.persist(newEvent)
      }
  }
}
