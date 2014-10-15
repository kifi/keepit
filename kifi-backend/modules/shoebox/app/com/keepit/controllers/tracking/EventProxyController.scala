package com.keepit.controllers.tracking

import com.keepit.common.controller._
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsObject, JsNumber, JsBoolean, JsString, JsNull, JsValue }
import play.api.mvc.RequestHeader

import com.google.inject.Inject

class EventProxyController @Inject() (
    val userActionsHelper: UserActionsHelper,
    heimdal: HeimdalServiceClient,
    heimdalContextBuilderFactoryBean: HeimdalContextBuilderFactory) extends UserActions with ShoeboxServiceController {

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

  def track() = UserAction(parse.tolerantJson) { request =>
    SafeFuture("event proxy") {
      val rawEvents: Seq[JsObject] = request.body.as[JsArray].value.map(_.as[JsObject])
      rawEvents.foreach { rawEvent =>
        val eventType = (rawEvent \ "event").as[String]
        val eventContext = (rawEvent \ "properties").as[JsObject]
        val context = jsObject2HeimdalContext(eventContext, request)
        val builder = heimdalContextBuilderFactoryBean.withRequestInfo(request)
        builder.addExistingContext(context)
        val fullcontext = builder.build

        heimdal.trackEvent(UserEvent(
          userId = request.userId,
          context = fullcontext,
          eventType = EventType(eventType)
        ))
        optionallySendUserUsedKifiEvent(request, fullcontext, eventType)
      }
    }
    NoContent
  }

  // integrate some events into used_kifi events as actions
  def optionallySendUserUsedKifiEvent(request: UserRequest[_], existingContext: HeimdalContext, triggeringEvent: String): Unit = {
    val validEvents = Set("user_viewed_page", "user_viewed_pane")
    if (validEvents.contains(triggeringEvent)) {
      val builder = heimdalContextBuilderFactoryBean()
      builder.addExistingContext(existingContext)
      triggeringEvent match {
        case "user_viewed_page" =>
          builder += ("action", "viewedSite")
          heimdal.trackEvent(UserEvent(request.userId, builder.build, UserEventTypes.USED_KIFI))
        case "user_viewed_pane" =>
          builder += ("action", "viewedPane")
          heimdal.trackEvent(UserEvent(request.userId, builder.build, UserEventTypes.USED_KIFI))
        case _ =>
      }
    }
  }
}
