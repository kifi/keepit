package com.keepit.heimdal

import com.keepit.common.time._

import org.joda.time.DateTime

import play.api.mvc.RequestHeader
import com.google.inject.{Inject, Singleton}
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.serializer.{Companion, TypeCode}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.model.ExperimentType


case class EventType(name: String)

object EventType {
  implicit val format = Json.format[EventType]
}

sealed trait ContextData
sealed trait SimpleContextData extends ContextData
case class ContextStringData(value: String) extends SimpleContextData
case class ContextDoubleData(value: Double) extends SimpleContextData
case class ContextBoolean(value: Boolean) extends SimpleContextData
case class ContextDate(value: DateTime) extends SimpleContextData
case class ContextList(values: Seq[SimpleContextData]) extends ContextData

case class EventContext(data: Map[String, ContextData])

object SimpleContextData {
  implicit val format = new Format[SimpleContextData] {
    def reads(json: JsValue): JsResult[SimpleContextData] = json match {
      case string: JsString => JsSuccess(string.asOpt[DateTime].map(ContextDate) getOrElse ContextStringData(string.as[String]))
      case JsNumber(value) => JsSuccess(ContextDoubleData(value.toDouble))
      case JsBoolean(bool) => JsSuccess(ContextBoolean(bool))
      case _ => JsError()
    }

    def writes(data: SimpleContextData): JsValue = data match {
      case ContextStringData(value) => JsString(value)
      case ContextDoubleData(value) => JsNumber(value)
      case ContextBoolean(value) => JsBoolean(value)
      case ContextDate(value) => Json.toJson(value)
    }
  }

  implicit def toContextStringData(value: String) = ContextStringData(value)
  implicit def toContextDoubleData[T <% Double](value: T) = ContextDoubleData(value)
  implicit def toContextBoolean(value: Boolean) = ContextBoolean(value)
  implicit def toContextDate(value: DateTime) = ContextDate(value)
}

object ContextData {
  implicit val format = new Format[ContextData] {
    def reads(json: JsValue): JsResult[ContextData] = json match {
      case list: JsArray => Json.fromJson[Seq[SimpleContextData]](list) map ContextList
      case _ => Json.fromJson[SimpleContextData](json)
    }

    def writes(data: ContextData): JsValue = data match {
      case ContextList(values) => Json.toJson(values)
      case simpleData: SimpleContextData => Json.toJson(simpleData)
    }
  }
}

object EventContext {
  implicit val format = new Format[EventContext] {

    def reads(json: JsValue): JsResult[EventContext] = {
      val data = json match {
        case obj: JsObject => Json.fromJson[Map[String, ContextData]](obj)
        case _ => return JsError()
      }
      data.map(EventContext(_))
    }

    def writes(context: EventContext) : JsValue = Json.toJson(context.data)
  }
}

class EventContextBuilder {
  val data = new scala.collection.mutable.HashMap[String, ContextData]()

  def +=[T <% SimpleContextData](key: String, value: T) : Unit = data(key) = value
  def +=[T <% SimpleContextData](key: String, values: Seq[T]) : Unit = data(key) = ContextList(values.map(identity[SimpleContextData](_)))
  def build : EventContext = EventContext(data.toMap)

  def addService(serviceDiscovery: ServiceDiscovery): Unit = {
    this += ("serviceVersion", serviceDiscovery.myVersion.value)
    serviceDiscovery.thisInstance.map { instance =>
      this += ("serviceInstance", instance.instanceInfo.instanceId.id)
      this += ("serviceZone", instance.instanceInfo.availabilityZone)
    }
  }

  def addRequest(request: RequestHeader, remoteAddress: Option[String]): Unit = {
    this += ("remoteAddress", remoteAddress orElse request.headers.get("X-Forwarded-For") getOrElse request.remoteAddress)
    this += ("doNotTrack", request.headers.get("do-not-track").exists(_ == "1"))
    addUserAgent(request.headers.get("User-Agent").getOrElse(""))

    request match {
      case authRequest: AuthenticatedRequest[_] =>
        authRequest.kifiInstallationId.foreach { id => this += ("kifiInstallationId", id.toString) }
        addExperiments(authRequest.experiments)
      case _ =>
    }
  }

  def addExperiments(experiments: Set[ExperimentType]): Unit = {
    this += ("experiments", experiments.map(_.value).toSeq)
    this += ("userStatus", ExperimentType.getUserStatus(experiments))
  }

  def addUserAgent(userAgent: String): Unit = {
    this += ("userAgent", userAgent)
  }
}

@Singleton
class EventContextBuilderFactory @Inject() (serviceDiscovery: ServiceDiscovery) {
  def apply(): EventContextBuilder = {
    val contextBuilder = new EventContextBuilder()
    contextBuilder.addService(serviceDiscovery)
    contextBuilder
  }

  def apply(request: RequestHeader, remoteAddress: Option[String] = None): EventContextBuilder = {
    val contextBuilder = apply()
    contextBuilder.addRequest(request, remoteAddress)
    contextBuilder
  }
}

sealed trait HeimdalEvent {
  val context: EventContext
  val eventType: EventType
  val time: DateTime
}

object HeimdalEvent {
  private val typeCodeMap = TypeCode.typeCodeMap[HeimdalEvent](UserEvent.typeCode, SystemEvent.typeCode)
  def getTypeCode(code: String) = typeCodeMap(code.toLowerCase)

  implicit val format = new Format[HeimdalEvent] {
    def writes(event: HeimdalEvent) = event match {
      case e: UserEvent => Companion.writes(e)
      case e: SystemEvent => Companion.writes(e)
    }
    private val readsFunc = Companion.reads(UserEvent, SystemEvent) // optimization
    def reads(json: JsValue) = readsFunc(json)
  }
}

case class UserEvent(
  userId: Long,
  context: EventContext,
  eventType: EventType,
  time: DateTime = currentDateTime
) extends HeimdalEvent {
  override def toString(): String = s"UserEvent[user=$userId,type=${eventType.name},time=$time]"
}

object UserEvent extends Companion[UserEvent] {
  implicit val format = Json.format[UserEvent]
  implicit val typeCode = TypeCode("user")
}

case class SystemEvent(context: EventContext, eventType: EventType, time: DateTime = currentDateTime) extends HeimdalEvent {
  override def toString(): String = s"SystemEvent[type=${eventType.name},time=$time]"
}

object SystemEvent extends Companion[SystemEvent] {
  implicit val format = Json.format[SystemEvent]
  implicit val typeCode = TypeCode("system")
}

case class EventDescriptor(
  name: EventType,
  description: Option[String] = None,
  mixpanel: Boolean = false
)

object EventDescriptor {
  implicit val format: Format[EventDescriptor] = (
    (__ \ 'name).format[EventType] and
      (__ \ 'description).formatNullable[String] and
      (__ \ 'mixpanel).format[Boolean]
    )(EventDescriptor.apply, unlift(EventDescriptor.unapply))
}
