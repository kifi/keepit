package com.keepit.heimdal

import com.keepit.common.time._
import com.keepit.common.healthcheck.HealthcheckPlugin

import org.joda.time.DateTime

import reactivemongo.bson.{BSONDocument, BSONDateTime, BSONValue, BSONLong, BSONString, BSONDouble, BSONArray}
import reactivemongo.api.collections.default.BSONCollection

import play.api.libs.json.{Json, Format, JsResult, JsError, JsSuccess, JsObject, JsValue, JsArray, JsNumber, JsString}

case class UserEventType(name: String)

object UserEventType {
  implicit val format = Json.format[UserEventType]
}

case class UserEventContext(data: Map[String, Seq[Either[String, Double]]])

object UserEventContext {
  implicit val format = new Format[UserEventContext] {

    def reads(json: JsValue): JsResult[UserEventContext] = {
      val map = json match {
        case obj: JsObject => obj.value.mapValues{ value =>
          val seq : Seq[Either[String, Double]] = value match {
            case arr: JsArray => arr.value.map{ _ match{
                case JsNumber(x) => Right[String, Double](x.doubleValue)
                case JsString(s) => Left[String, Double](s)
                case _ => return JsError()
              } 
            }
            case _ => return JsError()
          }
          seq
        }
        case _ => return JsError()
      }
      JsSuccess(UserEventContext(Map[String, Seq[Either[String, Double]]](map.toSeq :_*)))
    }

    def writes(obj: UserEventContext) : JsValue = {
      JsObject(obj.data.mapValues{ seq =>
        JsArray(seq.map{ _ match {
          case Left(s)  => JsString(s)
          case Right(x) => JsNumber(x) 
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

trait UserEventLoggingRepo extends BufferedMongoRepo[UserEvent] {
  val warnBufferSize = 1000
  val maxBufferSize = 2000

  private def contextToBSON(context: UserEventContext): BSONDocument = {
    BSONDocument(
      context.data.mapValues{ seq =>
        BSONArray(
          seq.map{ _ match {
            case Left(s)  => BSONString(s)
            case Right(x) => BSONDouble(x)
          }}
        )
      }
    )
  }

  def toBSON(event: UserEvent) : BSONDocument = {
    val userBatch: Long = event.userId / 1000 //Warning: This is a (neccessary!) index optimization. Changing this will require a database change!
    BSONDocument(Seq[(String, BSONValue)](
      "user_batch" -> BSONLong(userBatch),
      "user_id" -> BSONLong(event.userId),
      "context" -> contextToBSON(event.context),
      "event_type" -> BSONString(event.eventType.name),
      "time" -> BSONDateTime(event.time.getMillis)
    ))
  }

  def fromBSON(bson: BSONDocument): UserEvent = ???

}

class ProdUserEventLoggingRepo(val collection: BSONCollection, protected val healthcheckPlugin: HealthcheckPlugin) extends UserEventLoggingRepo

class DevUserEventLoggingRepo(val collection: BSONCollection, protected val healthcheckPlugin: HealthcheckPlugin) extends UserEventLoggingRepo {
  override def insert(obj: UserEvent) : Unit = {}
}
