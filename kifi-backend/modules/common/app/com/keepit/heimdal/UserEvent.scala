package com.keepit.heimdal

import com.keepit.common.time._

import org.joda.time.DateTime

import play.api.libs.json.{Json, Format, JsResult, JsError, JsSuccess, JsObject, JsValue, JsArray, JsNumber, JsString}
import com.keepit.common.controller.AuthenticatedRequest
import play.api.mvc.RequestHeader
import com.google.inject.{Inject, Singleton}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.zookeeper.ServiceDiscovery

case class UserEventType(name: String)

object UserEventType {
  implicit val format = Json.format[UserEventType]
}

sealed trait ContextData
case class ContextStringData(value: String) extends ContextData
case class ContextDoubleData(value: Double) extends ContextData

case class UserEventContext(data: Map[String, Seq[ContextData]]) extends AnyVal

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
          case ContextStringData(s) => JsString(s)
          case ContextDoubleData(x) => JsNumber(x)
        }})
      }.toSeq)
    }

  }
}

class UserEventContextBuilder {
  val data = new scala.collection.mutable.HashMap[String, Seq[ContextData]]()

  def +=(key: String, value: Double) : Unit = {
    val currentValues = data.getOrElse(key,Seq[ContextData]())
    data(key) = (currentValues :+ ContextDoubleData(value)).toSet.toSeq
  }

  def +=(key: String, value: Boolean) : Unit = {
    if (value) +=(key, 1.0) else +=(key, 0.0)
  }

  def +=(key: String, value: String) : Unit = {
    val currentValues = data.getOrElse(key,Seq[ContextData]())
    data(key) = (currentValues :+ ContextStringData(value)).toSet.toSeq
  }

  def build : UserEventContext = {
    UserEventContext(Map(data.toSeq:_*))
  }
}

@Singleton
class UserEventContextBuilderFactory @Inject() (serviceDiscovery: ServiceDiscovery) {
  def apply(request: Option[RequestHeader] = None): UserEventContextBuilder = {
    val contextBuilder = new UserEventContextBuilder()
    contextBuilder += ("serviceVersion", serviceDiscovery.myVersion.value)
    serviceDiscovery.thisInstance.map { instance =>
      contextBuilder += ("serviceInstance", instance.instanceInfo.instanceId.id)
      contextBuilder += ("serviceZone", instance.instanceInfo.availabilityZone)
    }

    request.map { req =>
      contextBuilder += ("remoteAddress", req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress))
      contextBuilder += ("userAgent", req.headers.get("User-Agent").getOrElse(""))
      contextBuilder += ("requestScheme", req.headers.get("X-Scheme").getOrElse(""))

      req match {
        case authRequest: AuthenticatedRequest[_] =>
          authRequest.kifiInstallationId.foreach { id => contextBuilder += ("kifiInstallationId", id.toString) }
          authRequest.experiments.foreach { experiment => contextBuilder += ("experiment", experiment.toString) }
        case _ =>
      }
    }

    contextBuilder
  }
}

case class UserEvent(
  userId: Long,
  context: UserEventContext,
  eventType: UserEventType,
  time: DateTime = currentDateTime
) {
  override def toString(): String = s"UserEvent[user=$userId,type=${eventType.name},time=$time]"
}

object UserEvent {
  implicit val format = Json.format[UserEvent]
}
