package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.analytics._
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db.{ExternalId, State}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._

import play.api.libs.json._

class ExtEventController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  EventPersister: EventPersister,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def logUserEvents = AuthenticatedJsonToJsonAction { request =>
    val json = request.body
    (json \ "version").as[Int] match {
      case 1 => createEventsFromPayload(json, request.user, request.experiments)
      case i => throw new Exception("Unknown events version: %s".format(i))
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
        log.debug("Created new event: %s".format(newEvent))

        EventPersister.persist(newEvent)
      }
  }
}
