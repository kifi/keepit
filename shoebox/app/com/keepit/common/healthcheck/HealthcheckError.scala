package com.keepit.common.healthcheck

import org.apache.commons.codec.binary.Base64
import java.security.MessageDigest
import scala.collection.mutable.MutableList

import com.keepit.common.actor.ActorFactory
import com.keepit.common.healthcheck.Healthcheck._
import com.keepit.common.db.ExternalId
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.EmailAddresses
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.time._
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.akka.FortyTwoActor

import play.api.templates.Html
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.pattern.ask
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent._
import org.joda.time.DateTime
import scala.concurrent.{Future, Await}
import com.google.inject.{Inject, Provider}
import scala.concurrent.duration._

case class HealthcheckErrorSignature(value: String) extends AnyVal

case class HealthcheckError(
  error: Option[Throwable] = None,
  method: Option[String] = None,
  path: Option[String] = None,
  callType: CallType,
  errorMessage: Option[String] = None,
  id: ExternalId[HealthcheckError] = ExternalId(),
  createdAt: DateTime = currentDateTime) {

  lazy val signature: HealthcheckErrorSignature = {
    val permText: String =
      causeStacktraceHead(4).getOrElse(errorMessage.getOrElse("")) +
      path.getOrElse("") +
      method.getOrElse("") +
      callType.toString
    val binaryHash = MessageDigest.getInstance("MD5").digest(permText.getBytes("UTF-8"))
    HealthcheckErrorSignature(new String(new Base64().encode(binaryHash), "UTF-8"))
  }

  def causeStacktraceHead(depth: Int, throwable: Option[Throwable] = error): Option[String] = throwable match {
    case None => None
    case Some(t) =>
      causeStacktraceHead(depth, Option(t.getCause)) match {
        case Some(msg) => Some(msg)
        case None => Some(t.getStackTrace().take(depth).map(e => e.getClassName + e.getLineNumber).mkString(":"))
      }
  }

  private def formatStackElement(e: StackTraceElement) = {
    def className(klazz: String) =
      if (klazz.contains("com.keepit")) s"""<span style="color:blue; font-size: 13px; ">$klazz</span>"""
      else s"""<span style="color:black; font-size: 13px;">$klazz</span>"""
    s"""${className(e.getClassName)}.<span style="color:black; font-size: 13px; font-style: italic;">${e.getMethodName}</span><span style="color:grey; font-size: 10px;">(${e.getFileName}:${e.getLineNumber})</span>"""
  }

  lazy val stackTraceHtml: String = {
    def causeString(throwableOptions: Option[Throwable]): String = throwableOptions match {
      case None => "--"
      case Some(t) if (t.getStackTrace().exists({e =>
        e.getClassName.contains("play.api.Application") && e.getMethodName.contains("handleError")
      })) => causeString(Option(t.getCause))
      case Some(t) =>
        s"""<span style="color:red; font-size: 13px; font-style: bold;">Cause:</span><br/>
            <span style="color:green; font-size: 16px; font-style: bold;">${t.toString}</span>\n<br/>
            ${(t.getStackTrace() map formatStackElement mkString "\n<br/> ")}<br/>
            ${causeString(Option(t.getCause))}"""
    }
    causeString(error)
  }

  lazy val subjectName: String = {
    def cause(t: Throwable): Throwable = Option(t.getCause()) match {
      case None => t
      case Some(c) => cause(c)
    }
    error match {
      case None =>
        errorMessage.getOrElse(path.getOrElse(callType.toString()))
      case Some(t) =>
        val source = cause(t)
        val message = source.getMessage().replaceAll("\\d", "*")
        val shortMessage = if (message.length > 59) message.substring(0, 60) else message
        s"${source.getClass().toString} : $shortMessage..."
    }
  }

  lazy val titleHtml: String = {
    def causeString(throwableOptions: Option[Throwable]): String = throwableOptions match {
      case None => "[No Cause]"
      case Some(t) => s"""${t.getClass.getName}: ${t.getMessage}\n<br/> &nbsp; <span style="color:red; font-size: 13px; font-style: bold;">Cause:</span> ${causeString(Option(t.getCause))}"""
    }
    (error map (e => causeString(error))).getOrElse(errorMessage.getOrElse(this.toString))
  }
 def toHtml: String = {
    val message = new StringBuilder("%s: [%s] Error during call of type %s".format(createdAt, id, callType))
    method.map { m =>
      message ++= s"""<br/><b>http method</b> <span style="color:blue; font-size: 13px; font-style: italic;">[$m]</span>"""
    }
    path.map { p =>
      message ++= s"""<br/><b>path</b> <span style="color:blue; font-size: 13px; font-style: italic;">[$p]</span>"""
    }
    errorMessage.map { em =>
      message ++= s"""<br/><b>error message</b> <span style="color:red; font-size: 13px; font-style: italic;">[${em.replaceAll("\n", "\n<br/>")}]</span>"""
    }
    error.map { e =>
      message ++= "<br/><b>Exception stack trace:\n</b><br/>"
      message ++= stackTraceHtml
    }

    message.toString()
  }
}
