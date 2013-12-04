package com.keepit.heimdal

import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.zookeeper.ServiceDiscovery
import play.api.mvc.RequestHeader
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.model.ExperimentType
import com.google.inject.{Inject, Singleton}

sealed trait ContextData
sealed trait SimpleContextData extends ContextData
case class ContextStringData(value: String) extends SimpleContextData
case class ContextDoubleData(value: Double) extends SimpleContextData
case class ContextBoolean(value: Boolean) extends SimpleContextData
case class ContextDate(value: DateTime) extends SimpleContextData
case class ContextList(values: Seq[SimpleContextData]) extends ContextData

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
  implicit def toContextDoubleData[T](value: T)(implicit toDouble: T => Double) = ContextDoubleData(value)
  implicit def toContextBoolean(value: Boolean) = ContextBoolean(value)
  implicit def toContextDate(value: DateTime) = ContextDate(value)

  implicit def fromContextStringData(simpleContextData: SimpleContextData): Option[String] = Some(simpleContextData) collect { case ContextStringData(value) => value }
  implicit def fromContextDoubleData(simpleContextData: SimpleContextData): Option[Double] = Some(simpleContextData) collect { case ContextDoubleData(value) => value }
  implicit def fromContextBoolean(simpleContextData: SimpleContextData): Option[Boolean] = Some(simpleContextData) collect { case ContextBoolean(value) => value }
  implicit def fromContextDate(simpleContextData: SimpleContextData) = Some(simpleContextData) collect { case ContextDate(value) => value }
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

case class HeimdalContext(data: Map[String, ContextData]) {
  def get[T](key: String)(implicit fromSimpleContextData: SimpleContextData => Option[T]): Option[T] = for {
    simpleContextData <- data.get(key) collect { case data: SimpleContextData => data }
    value <- fromSimpleContextData(simpleContextData)
  } yield value

  def getSeq[T](key: String)(implicit fromSimpleContextData: SimpleContextData => Option[T]): Option[Seq[T]] = for {
    ContextList(values) <- data.get(key)
    unboxedValues <- Some(values.map(fromSimpleContextData).flatten) if unboxedValues.length == values.length
  } yield unboxedValues
}

object HeimdalContext {
  implicit val format = new Format[HeimdalContext] {

    def reads(json: JsValue): JsResult[HeimdalContext] = {
      val data = json match {
        case obj: JsObject => Json.fromJson[Map[String, ContextData]](obj)
        case _ => return JsError()
      }
      data.map(HeimdalContext(_))
    }

    def writes(context: HeimdalContext) : JsValue = Json.toJson(context.data)
  }
}

class EventContextBuilder {
  val data = new scala.collection.mutable.HashMap[String, ContextData]()

  def +=[T ](key: String, value: T)(implicit toSimpleContextData: T => SimpleContextData) : Unit = data(key) = value
  def +=[T](key: String, values: Seq[T])(implicit toSimpleContextData: T => SimpleContextData) : Unit = data(key) = ContextList(values.map(toSimpleContextData))
  def build : HeimdalContext = HeimdalContext(data.toMap)

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
