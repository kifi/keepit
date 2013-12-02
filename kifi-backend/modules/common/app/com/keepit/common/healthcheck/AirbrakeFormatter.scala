package com.keepit.common.healthcheck

import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.db.ExternalId
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
        e.getFileName != "AbstractDispatcher.scala"))
}

class AirbrakeFormatter(val apiKey: String, val playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery) {

  val deploymentMessage: String = {
    val repo = "https://github.com/FortyTwoEng/keepit"
    val version = service.currentVersion
    s"api_key=$apiKey&deploy[rails_env]=$modeToRailsNaming&deploy[scm_repository]=$repo&deploy[scm_revision]=$version"
  }

  private def formatCauseStacktrace(causeOpt: Option[ErrorWithStack]): NodeSeq = causeOpt match {
    case Some(error) =>
      {<line method="" file={"========== Cause ========== " + error.toString} number="=========="/>} ++
      formatStacktrace(error) ++
      formatCauseStacktrace(error.cause)
    case None =>
      Seq()
  }

  private def formatStacktrace(error: ErrorWithStack) = {
    <line method={error.toString} file={error.stack.head.getFileName} number={error.stack.head.getLineNumber.toString}/> ++ {
      error.stack.map(e => {
        <line method={ignoreAnonfun(e.getClassName) + "#" + e.getMethodName} file={e.getFileName} number={e.getLineNumber.toString}/>
      })
    }
  }

  private def ignoreAnonfun(klazz: String) = klazz.
    replaceAll("""\$\$anonfun.*""", "[a]").
    replaceAll("""\$\$anon""", "[a]").
    replaceAll("""\$[0-9]""", "").
    replaceAll("""\$class""", "")

  private def formatParams(params: Map[String,Seq[String]]) = params.isEmpty match {
    case false =>
      (<params>{params.flatMap(e => {
          <var key={e._1}>{e._2.mkString(" ")}</var>
      })}</params>)::Nil
    case true => Nil
  }

  private def formatHeaders(params: Map[String, Seq[String]], id: ExternalId[AirbrakeError]) = params.isEmpty match {
    case false =>
      (<session>
        <var key="InternalErrorId">{id.id}</var>
        {params.flatMap(e => {
          <var key={e._1}>{e._2.mkString(" ")}</var>
        }
      )}</session>)::Nil
    case true => Nil
  }

  private def noticeRequest(url: String, params: Map[String, Seq[String]], method: Option[String], headers: Map[String, Seq[String]], id: ExternalId[AirbrakeError]) =
    <request>
      <url>{url}</url>
      <component/>
      { formatParams(params) }
      { method.map(m => <action>{m}</action>).getOrElse(<action/>) }
      { formatHeaders(headers.toMap, id) }
    </request>

  def noticeError(error: ErrorWithStack, message: Option[String]) =
    <error>
      <class>{ error.rootCause.error.getClass.getName }</class>
      <message>{
        val instance = serviceDiscovery.thisInstance
        val leader = serviceDiscovery.isLeader()
        val errorString = error.rootCause.error match {
          case _: DefaultAirbrakeException => ""
          case e: Throwable => e.toString()
        }
        s"[${instance.map(_.id.id).getOrElse("NA")}${if(leader) "L" else "_"}]${message.getOrElse("")} ${errorString}".trim
        }</message>
      <backtrace>
        { formatStacktrace(error) ++ formatCauseStacktrace(error.cause) }
      </backtrace>
    </error>

  private def noticeEntities(error: AirbrakeError) =
    (Some(noticeError(ErrorWithStack(error.exception), error.trimmedMessage)) :: error.url.map{u => noticeRequest(u, error.params, error.method, error.headers, error.id)} :: Nil).flatten

  //http://airbrake.io/airbrake_2_3.xsd
  private[healthcheck] def format(error: AirbrakeError) =
    <notice version="2.3">
      <api-key>{apiKey}</api-key>
      <notifier>
        <name>S42</name>
        <version>0.0.1</version>
        <url>http://admin.kifi.com/admin</url>
      </notifier>
      { noticeEntities(error) }
      <server-environment>
        <project-root>{service.currentService}</project-root>
        <environment-name>{modeToRailsNaming}</environment-name>
        <app-version>{service.currentVersion}</app-version>
        <hostname>{service.baseUrl}</hostname>
      </server-environment>
    </notice>

  lazy val modeToRailsNaming = playMode match {
    case Test => "test"
    case Prod => "production"
    case Dev => "development"
  }
}
