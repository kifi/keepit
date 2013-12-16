package com.keepit.heimdal

import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.zookeeper.ServiceDiscovery
import play.api.mvc.RequestHeader
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.model.{BookmarkSource, ExperimentType}
import com.google.inject.{Inject, Singleton}
import com.keepit.common.net.UserAgent
import com.keepit.common.time.DateTimeJsonFormat

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
      case ContextDate(value) => DateTimeJsonFormat.writes(value)
    }
  }

  implicit def toContextStringData(value: String) = ContextStringData(value)
  implicit def toContextDoubleData[T](value: T)(implicit toDouble: T => Double) = ContextDoubleData(value)
  implicit def toContextBoolean(value: Boolean) = ContextBoolean(value)
  implicit def toContextDate(value: DateTime) = ContextDate(value)

  implicit def fromContextStringData(simpleContextData: SimpleContextData): Option[String] = Some(simpleContextData) collect { case ContextStringData(value) => value }
  implicit def fromContextDoubleData(simpleContextData: SimpleContextData): Option[Double] = Some(simpleContextData) collect { case ContextDoubleData(value) => value }
  implicit def fromContextBoolean(simpleContextData: SimpleContextData): Option[Boolean] = Some(simpleContextData) collect { case ContextBoolean(value) => value }
  implicit def fromContextDate(simpleContextData: SimpleContextData): Option[DateTime] = Some(simpleContextData) collect { case ContextDate(value) => value }
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

  val empty = HeimdalContext(Map.empty)
}

class HeimdalContextBuilder {
  val data = new scala.collection.mutable.HashMap[String, ContextData]()

  def +=[T ](key: String, value: T)(implicit toSimpleContextData: T => SimpleContextData) : Unit = data(key) = value
  def +=[T](key: String, values: Seq[T])(implicit toSimpleContextData: T => SimpleContextData) : Unit = data(key) = ContextList(values.map(toSimpleContextData))
  def build : HeimdalContext = HeimdalContext(data.toMap)

  def addServiceInfo(serviceDiscovery: ServiceDiscovery): Unit = {
    this += ("serviceVersion", serviceDiscovery.myVersion.value)
    serviceDiscovery.thisInstance.map { instance =>
      this += ("serviceInstance", instance.instanceInfo.instanceId.id)
      this += ("serviceZone", instance.instanceInfo.availabilityZone)
    }
  }

  def addRequestInfo(request: RequestHeader): Unit = {
    this += ("doNotTrack", request.headers.get("do-not-track").exists(_ == "1"))
    addRemoteAddress(request.headers.get("X-Forwarded-For") getOrElse request.remoteAddress)
    addUserAgent(request.headers.get("User-Agent").getOrElse(""))

    request match {
      case authRequest: AuthenticatedRequest[_] =>
        authRequest.kifiInstallationId.foreach { id => this += ("kifiInstallationId", id.toString) }
        addExperiments(authRequest.experiments)
      case _ =>
    }
  }

  def addRemoteAddress(remoteAddress: String) = this += ("remoteAddress", remoteAddress)

  def addExperiments(experiments: Set[ExperimentType]): Unit = {
    this += ("experiments", experiments.map(_.value).toSeq)
    this += ("userStatus", ExperimentType.getUserStatus(experiments))
  }

  def addUserAgent(userAgent: String): Unit = {
    this += ("userAgent", userAgent)
    userAgent match {
      case UserAgent.iPhonePattern(appName, appVersion, buildSuffix, device, os, osVersion) =>
        this += ("device", device)
        this += ("os", os)
        this += ("osVersion", osVersion)
        this += ("client", "Kifi App")
        this += ("clientVersion", appVersion)
        this += ("clientBuild", appVersion + buildSuffix)
      case _ =>
        val agent = UserAgent.parser.parse(userAgent)
        this += ("device", agent.getDeviceCategory.getName)
        this += ("os", agent.getOperatingSystem.getFamilyName)
        this += ("osVersion", agent.getOperatingSystem.getName)
        this += ("client", agent.getName)
        this += ("clientVersion", agent.getVersionNumber.getMajor)
        this += ("clientBuild", agent.getVersionNumber.toVersionString)
    }
  }
}

@Singleton
class HeimdalContextBuilderFactory @Inject() (serviceDiscovery: ServiceDiscovery) {
  def apply(): HeimdalContextBuilder = {
    val contextBuilder = new HeimdalContextBuilder()
    contextBuilder.addServiceInfo(serviceDiscovery)
    contextBuilder
  }

  def withRequestInfo(request: RequestHeader): HeimdalContextBuilder = {
    val contextBuilder = apply()
    contextBuilder.addRequestInfo(request)
    contextBuilder
  }

  def withRequestInfoAndSource(request: RequestHeader, source: BookmarkSource) = {
    val contextBuilder = withRequestInfo(request)
    contextBuilder += ("source", source.value)
    contextBuilder
  }
}
