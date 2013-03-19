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

import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.async._
import com.keepit.model._
import com.keepit.serializer.{PersonalSearchResultPacketSerializer => RPS}
import java.sql.Connection
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Hit
import com.keepit.search.graph._
import com.keepit.search._
import com.keepit.common.social.UserWithSocial
import com.keepit.search.ArticleSearchResultStore
import com.keepit.common.controller.BrowserExtensionController
import com.keepit.common.analytics._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.analytics.reports._

import com.google.inject.{Inject, Singleton}

@Singleton
class ExtEventController @Inject() (
  db: Database,
  userExperimentRepo: UserExperimentRepo,
  userRepo: UserRepo,
  persistEventPlugin: PersistEventPlugin)
    extends BrowserExtensionController {

  def logUserEvents = AuthenticatedJsonAction { request =>
    val userId = request.userId

    val json = try {
      request.body.asJson.get
    } catch {
      case ex: java.util.NoSuchElementException =>
        log.error(s"Bad event json payload from user id ${request.userId.id}\n${request.body}")
        throw ex
    }
    (json \ "version").as[Int] match {
      case 1 => createEventsFromPayload(json, userId)
      case i => throw new Exception("Unknown events version: %s".format(i))
    }
    Ok(JsObject(Seq("stored" -> JsString("ok"))))
  }

  private def createEventsFromPayload(params: JsValue, userId: Id[User]) = {
    val logRecievedTime = currentDateTime

    val (user, experiments) = db.readOnly{ implicit session =>
      (userRepo.get(userId),
       userExperimentRepo.getByUser(userId) map (_.experimentType))
    }

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

        persistEventPlugin.persist(newEvent)
      }
  }
}
