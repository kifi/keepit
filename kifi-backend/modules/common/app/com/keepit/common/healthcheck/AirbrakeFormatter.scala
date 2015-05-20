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
