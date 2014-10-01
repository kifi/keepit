package com.keepit.controllers.tracking

import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsObject, JsNumber, JsBoolean, JsString, JsNull, JsValue }
import play.api.mvc.RequestHeader

import com.google.inject.Inject

class EventProxyController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    heimdal: HeimdalServiceClient,
    heimdalContextBuilderFactoryBean: HeimdalContextBuilderFactory) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  private def jsValue2SimpleContextData(datum: JsValue): SimpleContextData = {
    datum match {
      case JsNumber(x) => ContextDoubleData(x.toDouble)
      case JsBoolean(b) => ContextBoolean(b)
      case JsString(s) => ContextStringData(s)
      case huh => ContextStringData(huh.toString) //unexpected, trying our best not to loose data
    }
  }

  private def jsObject2HeimdalContext(data: JsObject, request: RequestHeader): HeimdalContext = {
    val contextBuilder = heimdalContextBuilderFactoryBean.withRequestInfo(request)
    data.fields.foreach {
      case (name, datum) =>
        datum match {
          case JsArray(a) => (name, a.map(jsValue2SimpleContextData(_)))
          case x => contextBuilder += (name, jsValue2SimpleContextData(x))
        }
    }
    contextBuilder.build
  }

  def track() = JsonAction.authenticatedParseJson { request =>
    SafeFuture("event proxy") {
      val rawEvents: Seq[JsObject] = request.body.as[JsArray].value.map(_.as[JsObject])
      rawEvents.foreach { rawEvent =>
        val eventType = (rawEvent \ "event").as[String]
        val eventContext = (rawEvent \ "properties").as[JsObject]
        heimdal.trackEvent(UserEvent(
          userId = request.userId,
          context = jsObject2HeimdalContext(eventContext, request),
          eventType = EventType(eventType)
        ))
      }
    }
    NoContent
  }

}
