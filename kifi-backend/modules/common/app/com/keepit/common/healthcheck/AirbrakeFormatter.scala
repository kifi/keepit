package com.keepit.common.healthcheck

import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.db.{ Id, ExternalId }
import com.google.inject.Inject
import com.google.inject.ImplementedBy
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.AlertingActor
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.logging.Logging
import com.keepit.common.net._
import com.keepit.common.strings._

import play.api.Mode._

import akka.actor._

import java.io._
import java.net._
import play.api.libs.json._

import scala.xml._

import play.api.mvc._

import AirbrakeError.MaxMessageSize
import com.keepit.model.User

case class ErrorWithStack(error: Throwable, stack: Seq[StackTraceElement]) {
  override def toString(): String = error.toString.abbreviate(MaxMessageSize)
  val cause: Option[ErrorWithStack] = Option(error.getCause).map(e => ErrorWithStack(e))
  val rootCause: ErrorWithStack = cause.map(c => c.rootCause).getOrElse(this)
}

object ErrorWithStack {
  def apply(error: Throwable): ErrorWithStack =
    ErrorWithStack(error,
      error.getStackTrace.filter(
        e => e != null &&
          e.getFileName != null &&
          !e.getClassName.startsWith("org.jboss.netty") &&
          !e.getClassName.startsWith("com.ning.http") &&
          !e.getClassName.startsWith("scala.collection.IterableLike") &&
          !e.getClassName.startsWith("scala.collection.AbstractIterable") &&
          !e.getClassName.startsWith("scala.collection.TraversableLike") &&
          !e.getClassName.startsWith("scala.collection.AbstractTraversable") &&
          !e.getFileName.contains("Airbrake") &&
          e.getFileName != "Option.scala" &&
          e.getFileName != "Action.scala" &&
          e.getFileName != "ForkJoinTask.java" &&
          e.getFileName != "ForkJoinPool.java" &&
          e.getFileName != "ForkJoinWorkerThread.java" &&
          e.getFileName != "Threads.scala" &&
          e.getFileName != "Promise.scala" &&
          e.getFileName != "Future.scala" &&
          e.getFileName != "AbstractDispatcher.scala")
        .take(AirbrakeError.MaxStackTrace))
}

class JsonAirbrakeFormatter(val apiKey: String, val playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery) {
  val deploymentMessage: String = {
    val repo = "https://github.com/kifi/keepit"
    val version = service.currentVersion
    s"api_key=$apiKey&deploy[rails_env]=$modeToRailsNaming&deploy[scm_repository]=$repo&deploy[scm_revision]=$version"
  }

  case class JsonAirbrakeBacktrace(file: String, function: String, line: Option[Int], column: Option[Int])
  case class JsonAirbrakeError(`type`: String, message: String, backtrace: Seq[JsonAirbrakeBacktrace])
  case class JsonAirbrakeNotifier(name: String, version: String, url: String)
  case class JsonAirbrakeContext(os: Option[String], language: Option[String], environment: Option[String],
    version: Option[String], url: Option[String], rootDirectory: Option[String],
    userId: Option[String], userName: Option[String], userEmail: Option[String])
  case class JsonAirbrakeEnvironment(service: String, mode: String, serviceVersion: String, hostname: String)
  case class JsonAirbrakeMessage(notifier: JsonAirbrakeNotifier, errors: Seq[JsonAirbrakeError], context: JsonAirbrakeContext,
    environment: JsonAirbrakeEnvironment, session: Option[Map[String, String]], params: Option[Map[String, String]])

  object JsonAirbrakeBacktrace { implicit val formatter = Json.format[JsonAirbrakeBacktrace] }
  object JsonAirbrakeError { implicit val formatter = Json.format[JsonAirbrakeError] }
  object JsonAirbrakeNotifier { implicit val formatter = Json.format[JsonAirbrakeNotifier] }
  object JsonAirbrakeContext { implicit val formatter = Json.format[JsonAirbrakeContext] }
  object JsonAirbrakeEnvironment { implicit val formatter = Json.format[JsonAirbrakeEnvironment] }
  object JsonAirbrakeMessage { implicit val formatter = Json.format[JsonAirbrakeMessage] }

  private val notifier = JsonAirbrakeNotifier("S42", "0.0.2", "https://admin.kifi.com/admin")

  def format(airbrake: AirbrakeError): JsValue = {
    val errors: Seq[JsonAirbrakeError] = airbrakeErrors(airbrake)

    val message = JsonAirbrakeMessage(notifier, errors, context(airbrake), environment(), session(airbrake), params(airbrake))
    Json.toJson(message)
  }

  private def context(error: AirbrakeError) = {
    JsonAirbrakeContext(os = None, language = None, environment = Some(modeToRailsNaming),
      version = Some(service.currentVersion.toString), url = error.url, rootDirectory = Some(service.currentService.toString),
      userId = error.userId.map(_.toString), userName = error.userName, userEmail = None)
  }

  private def session(error: AirbrakeError) = {
    val (headers, id, userIdOpt, usernameOpt) = (error.headers, error.id, error.userId, error.userName)
    val result: Map[String, String] = headers.map { implicit header =>
      header._1 -> header._2.mkString(" ")
    }
    result ++ "Z-InternalErrorId" -> id.id
    userIdOpt.foreach(userId => { result ++ "Z-UserId" -> s"https://admin.kifi.com/admin/user/${userId.id}" })
    usernameOpt.foreach(username => { result ++ "Z-UserName" -> username })
    result.nonEmpty match {
      case true => Some(result)
      case false => None
    }
  }

  private def params(error: AirbrakeError) = {
    val result = error.params.map(header => header._1 -> header._2.mkString(" "))
    result.nonEmpty match {
      case true => Some(result)
      case false => None
    }
  }

  private def environment() = {
    val hostname = serviceDiscovery.thisInstance map { instance =>
      val info = instance.remoteService.amazonInstanceInfo
      s"https://console.aws.amazon.com/ec2/v2/home?region=us-west-1#Instances:instancesFilter=all-instances;instanceTypeFilter=all-instance-types;search=${info.instanceId}"
    } getOrElse "NA"
    JsonAirbrakeEnvironment(service.currentService.toString, modeToRailsNaming, service.currentVersion.toString, hostname)
  }

  private def getErrorMessage(error: ErrorWithStack, message: Option[String]) = {
    val instance = serviceDiscovery.thisInstance
    val leader = serviceDiscovery.isLeader()
    val errorString = error.rootCause.error match {
      case _: DefaultAirbrakeException => ""
      case e: Throwable => e.toString
    }
    val cleanMessage = message.map(_.replaceAll("Execution exception in null:null", "")).getOrElse("")
    s"[${instance.map(_.id.id).getOrElse("NA")}${if (leader) "L" else "_"}]$cleanMessage $errorString".trim
  }

  private def getAirbrakeStackTrace(error: ErrorWithStack): Seq[JsonAirbrakeBacktrace] = {
    val backtrace = error.stack map { implicit stack =>
      JsonAirbrakeBacktrace(stack.getFileName, ignoreAnonfun(stack.getClassName) + "#" + stack.getMethodName, Some(stack.getLineNumber), None)
    }
    error.cause map { implicit errorWithStack =>
      val stack: Seq[StackTraceElement] = errorWithStack.stack
      val cause = JsonAirbrakeBacktrace("======== Caused By: ", errorWithStack.error.toString + "========", None, None)
      cause +: getAirbrakeStackTrace(errorWithStack)
    } match {
      case Some(next) => backtrace ++ next
      case None => backtrace
    }
  }

  private def airbrakeErrors(airbrake: AirbrakeError): Seq[JsonAirbrakeError] = {
    val error = ErrorWithStack(airbrake.exception)

    val errorType: String = error.rootCause.error.getClass.getName
    val message: String = getErrorMessage(error, airbrake.trimmedMessage)
    val backtrace: Seq[JsonAirbrakeBacktrace] = getAirbrakeStackTrace(error).take(AirbrakeError.MaxStackTrace)
    Seq[JsonAirbrakeError](JsonAirbrakeError(errorType, message, backtrace))
  }
  private def ignoreAnonfun(klazz: String) = klazz.
    replaceAll("""\$\$anonfun.*""", "[a]").
    replaceAll("""\$\$anon""", "[a]").
    replaceAll("""\$[0-9]""", "").
    replaceAll("""\$class""", "")

  lazy val modeToRailsNaming = playMode match {
    case Test => "test"
    case Prod => "production"
    case Dev => "development"
  }
}

trait AirbrakeFormatter {
  private[healthcheck] def deploymentMessage: String
  private[healthcheck] def format(error: AirbrakeError): NodeSeq
  def noticeError(error: ErrorWithStack, message: Option[String]): NodeSeq
}

class AirbrakeFormatterImpl(val apiKey: String, val playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery) extends AirbrakeFormatter {

  val deploymentMessage: String = {
    val repo = "https://github.com/kifi/keepit"
    val version = service.currentVersion
    s"api_key=$apiKey&deploy[rails_env]=$modeToRailsNaming&deploy[scm_repository]=$repo&deploy[scm_revision]=$version"
  }

  private def formatCauseStacktrace(causeOpt: Option[ErrorWithStack]): NodeSeq = causeOpt match {
    case Some(error) =>
      { <line method="" file={ "========== Cause ========== " + error.toString } number="=========="/> } ++
        formatStacktrace(error) ++
        formatCauseStacktrace(error.cause)
    case None =>
      Seq()
  }

  private def formatStacktrace(error: ErrorWithStack) = {
    <line method={ error.toString() } file={ error.stack.headOption.map(_.getFileName).getOrElse("unknown") } number={ error.stack.headOption.map(_.getLineNumber).getOrElse(0).toString }/> ++ {
      error.stack.map(e => {
        <line method={ ignoreAnonfun(e.getClassName) + "#" + e.getMethodName } file={ e.getFileName } number={ e.getLineNumber.toString }/>
      })
    }
  }

  private def ignoreAnonfun(klazz: String) = klazz.
    replaceAll("""\$\$anonfun.*""", "[a]").
    replaceAll("""\$\$anon""", "[a]").
    replaceAll("""\$[0-9]""", "").
    replaceAll("""\$class""", "")

  private def formatParams(params: Map[String, Seq[String]]) = params.isEmpty match {
    case false =>
      <params>{
        params.flatMap(e => {
          <var key={ e._1 }>{ e._2.mkString(" ") }</var>
        })
      }</params> :: Nil
    case true => Nil
  }

  private def formatHeaders(params: Map[String, Seq[String]], id: ExternalId[AirbrakeError], userId: Option[Id[User]], userName: Option[String]) = {
    <session>
      {
        params.flatMap(e => {
          Some(<var key={ e._1 }>{ e._2.mkString(" ") }</var>)
        })
      }
      <var key="Z-InternalErrorId">{ id.id }</var>
      {
        ({ userId.map { id => <var key="Z-UserId">https://admin.kifi.com/admin/user/{ id.id }</var> } } ::
          { userName.map { name => <var key="Z-UserName">{ name }</var> } } :: Nil).flatten
      }
    </session> :: Nil
  }

  private def noticeRequest(url: String, params: Map[String, Seq[String]], method: Option[String], headers: Map[String, Seq[String]], id: ExternalId[AirbrakeError], userId: Option[Id[User]], userName: Option[String]) =
    <request>
      <url>{ url }</url>
      <component/>
      { formatParams(params) }
      { method.map(m => <action>{ m }</action>).getOrElse(<action/>) }
      { formatHeaders(headers.toMap, id, userId, userName) }
    </request>

  def noticeError(error: ErrorWithStack, message: Option[String]): NodeSeq =
    <error>
      <class>{ error.rootCause.error.getClass.getName }</class>
      <message>{
        val instance = serviceDiscovery.thisInstance
        val leader = serviceDiscovery.isLeader()
        val errorString = error.rootCause.error match {
          case _: DefaultAirbrakeException => ""
          case e: Throwable => e.toString
        }
        val cleanMessage = message.map(_.replaceAll("Execution exception in null:null", "")).getOrElse("")
        s"[${instance.map(_.id.id).getOrElse("NA")}${if (leader) "L" else "_"}]$cleanMessage $errorString".trim
      }</message>
      <backtrace>
        { formatStacktrace(error) ++ formatCauseStacktrace(error.cause) }
      </backtrace>
    </error>

  private def noticeEntities(error: AirbrakeError) =
    (Some(noticeError(ErrorWithStack(error.exception), error.trimmedMessage)) :: error.url.map { u =>
      noticeRequest(u, error.params, error.method, error.headers, error.id, error.userId, error.userName)
    } :: Nil).flatten

  //http://airbrake.io/airbrake_2_3.xsd
  private[healthcheck] def format(error: AirbrakeError): NodeSeq =
    <notice version="2.3">
      <api-key>{ apiKey }</api-key>
      <notifier>
        <name>S42</name>
        <version>0.0.1</version>
        <url>http://admin.kifi.com/admin</url>
      </notifier>
      { noticeEntities(error) }
      <server-environment>
        <project-root>{ service.currentService }</project-root>
        <environment-name>{ modeToRailsNaming }</environment-name>
        <app-version>{ service.currentVersion }</app-version>
        <hostname>{
          serviceDiscovery.thisInstance map { instance =>
            val info = instance.remoteService.amazonInstanceInfo
            s"https://console.aws.amazon.com/ec2/v2/home?region=us-west-1#Instances:instancesFilter=all-instances;instanceTypeFilter=all-instance-types;search=${info.instanceId}"
          } getOrElse "NA"
        }</hostname>
      </server-environment>
    </notice>

  lazy val modeToRailsNaming = playMode match {
    case Test => "test"
    case Prod => "production"
    case Dev => "development"
  }
}
