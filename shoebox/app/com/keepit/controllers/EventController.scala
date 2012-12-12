package com.keepit.controllers

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.http.ContentTypes
import com.keepit.controllers.CommonActions._
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.common.db.ExternalId
import com.keepit.common.async._
import com.keepit.model._
import com.keepit.inject._
import com.keepit.serializer.{PersonalSearchResultPacketSerializer => RPS}
import java.sql.Connection
import com.keepit.common.logging.Logging
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Hit
import com.keepit.search.graph._
import com.keepit.search._
import com.keepit.common.social.UserWithSocial
import org.apache.commons.lang3.StringEscapeUtils
import com.keepit.search.ArticleSearchResultStore
import com.keepit.common.controller.FortyTwoController
import play.api.libs.json._
import com.keepit.common.analytics._
import com.keepit.model._



object EventController extends FortyTwoController {

  def logUserEvents = AuthenticatedJsonAction { request =>
    val params = Json.parse(request.body.asFormUrlEncoded.get.get("payload").get.head)
    val userId = request.userId

    val events = (params \ "events") match {
      case JsArray(ev) => ev map { event =>
        event match {
          case o: JsObject => o
          case _: JsValue => throw new Exception()
        }
      }
      case _: JsValue => throw new Exception()
    }

    CX.withConnection { implicit conn =>
      events map { event =>
        val eventFamily = EventFamilies((event \ "eventFamily").as[String])
        val eventName = (event \ "eventName").as[String]
        val installId = ExternalId[KifiInstallation]((event \ "installId").as[String])
        val metaData = (event \ "metaData") match {
          case s: JsObject => s
          case s: JsValue => throw new Exception()
        }
        val prevEvents = (event \ "prevEvents") match {
          case JsArray(s) =>
            s map { ext =>
              ext match {
                case JsString(id) => ExternalId[Event](id)
                case _: JsValue => throw new Exception()
              }
            }
          case _: JsValue => throw new Exception()
        }
        Events.userEvent(eventFamily, eventName, userId, installId, metaData, prevEvents).persist
      }
    }

    Ok(JsObject(Seq("stored" -> JsString("ok"))))
  }
  def logEvent() = AuthenticatedJsonAction { request =>

    Ok("").as(ContentTypes.JSON)
  }


}
