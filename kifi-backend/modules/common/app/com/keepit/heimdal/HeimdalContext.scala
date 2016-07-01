package com.keepit.heimdal

import javax.xml.bind.DatatypeConverter

import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailTrackingParam
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.mvc.RequestHeader
import com.keepit.common.controller.UserRequest
import com.keepit.model._
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.net.{ URI, UserAgent }
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.common.mail.ElectronicMail
import com.keepit.social.SocialNetworkType
import scala.util.Try
import com.keepit.common.service.{ IpAddress, FortyTwoServices }
import com.keepit.common.amazon.MyInstanceInfo
import com.keepit.common.time._

sealed trait ContextData
sealed trait SimpleContextData extends ContextData
case class ContextStringData(value: String) extends SimpleContextData
case class ContextDoubleData(value: Double) extends SimpleContextData
case class ContextBoolean(value: Boolean) extends SimpleContextData
case class ContextDate(value: DateTime) extends SimpleContextData
case class ContextList(values: Seq[SimpleContextData]) extends ContextData

object ContextList {
  implicit def toContextList[E](values: Seq[E])(implicit toCtx: E => SimpleContextData): ContextList = ContextList(values map toCtx)
}

object SimpleContextData {
  implicit val format = new Format[SimpleContextData] {
    def reads(json: JsValue): JsResult[SimpleContextData] = json match {
      case string: JsString => string.validate[DateTime].map(ContextDate) orElse JsSuccess(ContextStringData(string.value))
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

  implicit def toContextStringData(value: String): SimpleContextData = ContextStringData(value)
  implicit def toContextDoubleData[T](value: T)(implicit toDouble: T => Double): SimpleContextData = ContextDoubleData(value)
  implicit def toContextBoolean(value: Boolean): SimpleContextData = ContextBoolean(value)
  implicit def toContextDate(value: DateTime): SimpleContextData = ContextDate(value)

  implicit def fromContextStringData(simpleContextData: SimpleContextData): Option[String] = Some(simpleContextData) collect { case ContextStringData(value) => value }
  implicit def fromContextDoubleData(simpleContextData: SimpleContextData): Option[Double] = Some(simpleContextData) collect { case ContextDoubleData(value) => value }
  implicit def fromContextBoolean(simpleContextData: SimpleContextData): Option[Boolean] = Some(simpleContextData) collect { case ContextBoolean(value) => value }
  implicit def fromContextDate(simpleContextData: SimpleContextData): Option[DateTime] = Some(simpleContextData) collect { case ContextDate(value) => value }
}

object ContextData {

  implicit val format = new Format[ContextData] {
    def reads(json: JsValue): JsResult[ContextData] = json match {
      case list: JsArray => Json.fromJson[Seq[SimpleContextData]](list) map ContextList.apply
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

    def writes(context: HeimdalContext): JsValue = Json.toJson(context.data)
  }

  val empty = HeimdalContext(Map.empty)
}

object HeimdalContextBuilder {
  val userFields = Set("userCreatedAt", "daysSinceUserJoined")
  def getUserFields(user: User): Map[String, ContextData] = {
    val daysSinceUserJoined = (currentDateTime.getMillis.toFloat - user.createdAt.getMillis) / (24 * 3600 * 1000)
    Map(
      "userCreatedAt" -> ContextDate(user.createdAt),
      "daysSinceUserJoined" -> ContextDoubleData(daysSinceUserJoined - daysSinceUserJoined % 0.0001)
    )
  }

  val experimentFields = Set("experiments", "userStatus")
  def getExperimentFields(experiments: Set[UserExperimentType]): Map[String, ContextData] = {
    Map(
      "experiments" -> ContextList(experiments.map(ex => ContextStringData(ex.value)).toSeq),
      "userStatus" -> ContextStringData(UserExperimentType.getUserStatus(experiments))
    ) ++ experiments.map { ex =>
        s"exp_${ex.value}}" -> ContextBoolean(true)
      }
  }
}

class HeimdalContextBuilder extends Logging {
  val data = new scala.collection.mutable.HashMap[String, ContextData]()

  def +=[T](key: String, value: T)(implicit toSimpleContextData: T => SimpleContextData): Unit = data(key) = value
  def +=[T](key: String, values: Seq[T])(implicit toSimpleContextData: T => SimpleContextData): Unit = data(key) = ContextList(values.map(toSimpleContextData))
  def ++=(moreData: Map[String, ContextData]): Unit = { data ++= moreData }
  def build: HeimdalContext = HeimdalContext(data.toMap)

  def addExistingContext(context: HeimdalContext): HeimdalContextBuilder = {
    this ++= context.data
    this
  }

  def addServiceInfo(thisService: FortyTwoServices, myAmazonInstanceInfo: MyInstanceInfo): Unit = {
    this += ("serviceVersion", thisService.currentVersion.value)
    this += ("serviceInstance", myAmazonInstanceInfo.info.instanceId.id)
    this += ("serviceZone", myAmazonInstanceInfo.info.availabilityZone)
  }

  private def addKifiClientAndVersion(request: RequestHeader): Unit = {

    val clientVersion = request.headers.get("X-Kifi-Client") getOrElse ""

    def add(clientName: String, version: KifiVersion): Unit = {
      this += ("client", clientName)
      this += ("clientVersion", version.toStringWithoutBuild)
      this += ("clientBuild", version.toString)
    }

    val parts = clientVersion.trim.split("\\s")
    if (parts.size == 2) {
      val (client, version) = (parts(0), parts(1))
      client match {
        case "BE" => add("browserExtension", KifiExtVersion(version))
        case "iOS" => add("iOSApp", KifiIPhoneVersion(version))
        case "iOS Extension" => add("iOSExtension", KifiIPhoneVersion(version))
        case "Android" => add("androidApp", KifiAndroidVersion(version))
        case _ => log.error(s"unsupported X-Kifi-Client header: ${clientVersion}")
      }
    }
  }

  def addRequestInfo(request: RequestHeader): Unit = {
    this += ("doNotTrack", request.headers.get("do-not-track").contains("1"))
    addRemoteAddress(IpAddress.fromRequest(request).ip)
    addUserAgent(request.headers.get("User-Agent").getOrElse(""))
    addKifiClientAndVersion(request)

    request match {
      case userRequest: UserRequest[_] =>
        this ++= HeimdalContextBuilder.getUserFields(userRequest.user)
        userRequest.kifiInstallationId.foreach { id => this += ("kifiInstallationId", id.toString) }
        addExperiments(userRequest.experiments)
        Try(SocialNetworkType(userRequest.identityId.get.providerId)).foreach { socialNetwork => this += ("identityProvider", socialNetwork.toString) }
      case _ =>
    }
  }

  def addRemoteAddress(remoteAddress: String) = this += ("remoteAddress", remoteAddress)

  def addExperiments(experiments: Set[UserExperimentType]): Unit = {
    this ++= HeimdalContextBuilder.getExperimentFields(experiments)
  }

  def addUserAgent(userAgent: String): Unit = {
    this += ("userAgent", userAgent)
    userAgent match {
      // These two are here for backwards compatiblity. Remove it soon!
      case UserAgent.iosAppRe(appName, appVersion, buildSuffix, device, os, osVersion) =>
        this += ("device", device)
        this += ("os", os)
        this += ("osVersion", osVersion)
        this += ("client", "iOSApp")
        this += ("clientVersion", appVersion)
        this += ("clientBuild", appVersion + buildSuffix)
      case UserAgent.iosAppDeviceRe(appName) =>
        this += ("device", "iphone")
        this += ("os", "iOS")
        this += ("client", "iOSApp")
      case UserAgent.androidAppRe(appName, os, osVersion, device) =>
        this += ("device", device)
        this += ("os", os)
        this += ("osVersion", osVersion)
        this += ("client", "androidApp")
      case _ =>
        val agent = UserAgent.parser.parse(userAgent)
        this += ("device", agent.getDeviceCategory.getName)
        this += ("os", agent.getOperatingSystem.getFamilyName)
        this += ("osVersion", agent.getOperatingSystem.getName)
        this += ("agent", agent.getName)
        this += ("agentVersion", agent.getVersionNumber.getMajor)
    }
  }

  def addUrlInfo(url: String): Unit = {
    this += ("url", url)
    URI.parse(url).foreach { uri =>
      uri.host.collect {
        case host =>
          this += ("host", host.name)
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

  def addDetailedEmailInfo(param: EmailTrackingParam): Unit = {
    param.subAction.foreach(v => this += ("subaction", v))
    param.tip.foreach { tip => this += ("emailTip", tip) }
    if (param.variableComponents.nonEmpty) this += ("emailComponents", param.variableComponents)

    param.auxiliaryData.foreach { ctx =>
      ctx.data.foreach { case (key, value) => data(key) = value }
    }
  }

  def addNotificationCategory(category: NotificationCategory): Unit = {
    val camelledCategory = category.category.toLowerCase.split("_") match { case Array(h, q @ _*) => h + q.map(_.capitalize).mkString }
    this += ("category", camelledCategory)
    NotificationCategory.ParentCategory.get(category).foreach { parentCategory => this += ("parentCategory", parentCategory) }
  }

  def anonymise(toBeRemoved: String*): Unit = {
    toBeRemoved.foreach(this.data.remove)
    this.data.get("remoteAddress").foreach { ip =>
      this.data += ("ip" -> ip) // ip address will be processed by Mixpanel to extract geolocation data but will not be displayed as a property
      this.data.remove("remoteAddress")
    }
    this.data.remove("kifiInstallationId")
    this.data.remove("userCreatedAt")
    this.data.remove("daysSinceUserJoined")
  }
}

@Singleton
class HeimdalContextBuilderFactory @Inject() (
    thisService: FortyTwoServices,
    myAmazonInstanceInfo: MyInstanceInfo) {

  def apply(): HeimdalContextBuilder = {
    val contextBuilder = new HeimdalContextBuilder()
    contextBuilder.addServiceInfo(thisService, myAmazonInstanceInfo)
    contextBuilder
  }

  def withRequestInfo(request: RequestHeader): HeimdalContextBuilder = {
    val contextBuilder = apply()
    contextBuilder.addRequestInfo(request)
    request.getQueryString("subsource").collect {
      case subsource if subsource.nonEmpty =>
        contextBuilder += ("subsource", subsource)
    }
    contextBuilder
  }

  def withRequestInfoAndSource(request: RequestHeader, source: KeepSource) = {
    val contextBuilder = withRequestInfo(request)
    contextBuilder += ("source", source.value)
    contextBuilder
  }
}
