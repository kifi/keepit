package com.keepit.heimdal

import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.zookeeper.ServiceDiscovery
import play.api.mvc.RequestHeader
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.model.{NotificationCategory, BookmarkSource, ExperimentType}
import com.google.inject.{Inject, Singleton}
import com.keepit.common.net.{Host, URI, UserAgent}
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.common.mail.ElectronicMail
import com.keepit.social.SocialNetworkType
import scala.util.Try
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.amazon.{MyAmazonInstanceInfo, AmazonInstanceInfo}

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

  def +=[T](key: String, value: T)(implicit toSimpleContextData: T => SimpleContextData) : Unit = data(key) = value
  def +=[T](key: String, values: Seq[T])(implicit toSimpleContextData: T => SimpleContextData) : Unit = data(key) = ContextList(values.map(toSimpleContextData))
  def build : HeimdalContext = HeimdalContext(data.toMap)

  def addServiceInfo(thisService: FortyTwoServices, myAmazonInstanceInfo: MyAmazonInstanceInfo): Unit = {
    this += ("serviceVersion", thisService.currentVersion.value)
    this += ("serviceInstance", myAmazonInstanceInfo.info.instanceId.id)
    this += ("serviceZone", myAmazonInstanceInfo.info.availabilityZone)
  }

  def addRequestInfo(request: RequestHeader): Unit = {
    this += ("doNotTrack", request.headers.get("do-not-track").exists(_ == "1"))
    addRemoteAddress(request.headers.get("X-Forwarded-For") getOrElse request.remoteAddress)
    addUserAgent(request.headers.get("User-Agent").getOrElse(""))

    request match {
      case authRequest: AuthenticatedRequest[_] =>
        authRequest.kifiInstallationId.foreach { id => this += ("kifiInstallationId", id.toString) }
        addExperiments(authRequest.experiments)
        Try(SocialNetworkType(authRequest.identity.identityId.providerId)).foreach { socialNetwork => this += ("identityProvider", socialNetwork.toString) }
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

  def addUrlInfo(url: String): Unit = {
    this += ("url", url)
    URI.parse(url).foreach { uri =>
      uri.host.collect { case host @ Host(domain) if domain.nonEmpty =>
        this += ("host", host.name)
        this += ("domain", domain.take(2).reverse.mkString("."))
        this += ("domainExtension", domain(0))
        this += ("domainName", domain(1))
      }
      uri.scheme.foreach { scheme => this += ("scheme", scheme) }
    }
  }

  def addEmailInfo(email: ElectronicMail): Unit = {
    this += ("channel", "email")
    this += ("emailId", email.id.map(_.id.toString).getOrElse(email.externalId.id))
    this += ("subject", email.subject)
    this += ("from", email.from.address)
    this += ("fromName", email.fromName.getOrElse(""))
    this.addNotificationCategory(email.category)
    email.inReplyTo.foreach { previousEmailId => this += ("inReplyTo", previousEmailId.id) }
    email.senderUserId.foreach { id => this += ("senderUserId", id.id) }
  }

  def addNotificationCategory(category: NotificationCategory): Unit = {
    val camelledCategory = category.category.toLowerCase().split("_") match { case Array(h, q @ _*)  => h + q.map(_.capitalize).mkString }
    this += ("category", camelledCategory)
    NotificationCategory.User.parentCategory.get(category).foreach { parentCategory => this += ("parentCategory", parentCategory) }
  }

  def anonymise(toBeRemoved: String*): Unit = {
    toBeRemoved.foreach(this.data.remove)
    this.data.get("remoteAddress").foreach { ip =>
      this.data += ("ip" -> ip) // ip address will be processed by Mixpanel to extract geolocation data but will not be displayed as a property
      this.data.remove("remoteAddress")
    }
    this.data.remove("kifiInstallationId")
  }
}

@Singleton
class HeimdalContextBuilderFactory @Inject() (
    thisService: FortyTwoServices,
    myAmazonInstanceInfo: MyAmazonInstanceInfo) {

  def apply(): HeimdalContextBuilder = {
    val contextBuilder = new HeimdalContextBuilder()
    contextBuilder.addServiceInfo(thisService, myAmazonInstanceInfo)
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
