package com.keepit.heimdal

import com.keepit.common.time._

import org.joda.time.DateTime

import play.api.libs.json.{Json, Format, JsResult, JsError, JsSuccess, JsObject, JsValue, JsArray, JsNumber, JsString}


case class UserEventType(name: String)

object UserEventType {
  implicit val format = Json.format[UserEventType]
}

sealed trait ContextData
case class ContextStringData(value: String) extends ContextData
case class ContextDoubleData(value: Double) extends ContextData

case class UserEventContext(data: Map[String, Seq[ContextData]])

object UserEventContext {
  implicit val format = new Format[UserEventContext] {

    def reads(json: JsValue): JsResult[UserEventContext] = {
      val map = json match {
        case obj: JsObject => obj.value.mapValues{ value =>
          val seq : Seq[ContextData] = value match {
            case arr: JsArray => arr.value.map{ _ match{
                case JsNumber(x) => ContextDoubleData(x.doubleValue)
                case JsString(s) => ContextStringData(s)
                case _ => return JsError()
              } 
            }
            case _ => return JsError()
          }
          seq
        }
        case _ => return JsError()
      }
      JsSuccess(UserEventContext(Map[String, Seq[ContextData]](map.toSeq :_*)))
    }

    def writes(obj: UserEventContext) : JsValue = {
      JsObject(obj.data.mapValues{ seq =>
        JsArray(seq.map{ _ match {
          case ContextStringData(s)  => JsString(s)
          case ContextDoubleData(x) => JsNumber(x) 
        }})
      }.toSeq)
    }

  }
}

case class UserEvent(
  userId: Long,
  context: UserEventContext,
  eventType: UserEventType,
  time: DateTime = currentDateTime
)


object UserEvent {
  implicit val format = Json.format[UserEvent]
}
