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
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.logging.Logging
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

case class HealthcheckError(error: Option[Throwable] = None, method: Option[String] = None,
    path: Option[String] = None, callType: CallType, errorMessage: Option[String] = None,
    id: ExternalId[HealthcheckError] = ExternalId(), createdAt: DateTime = currentDateTime) {

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
        case None =>
          Some(t.getStackTrace().take(depth).mkString)
      }
  }

  lazy val stackTraceHtml: String = {
    def causeString(throwableOptions: Option[Throwable]): String = throwableOptions match {
      case None => "[No Cause]"
      case Some(t) => (t.getStackTrace() mkString "\n<br/> &nbsp; ") + s"\n<br/> &nbsp; Cause: ${causeString(Option(t.getCause))}"
    }
    causeString(error)
  }

  lazy val titleHtml: String = {
    def causeString(throwableOptions: Option[Throwable]): String = throwableOptions match {
      case None => "[No Cause]"
      case Some(t) => s"${t.getClass.getName}: ${t.getMessage}\n<br/> &nbsp; Cause: ${causeString(Option(t.getCause))}"
    }
    (error map (e => causeString(error))).getOrElse(errorMessage.getOrElse(this.toString))
  }

  def toHtml: String = {
    val message = new StringBuilder("%s: [%s] Error during call of type %s".format(createdAt, id, callType))
    method.map { m =>
      message ++= "<br/>http method [%s]".format(m)
    }
    path.map { p =>
      message ++= "<br/>path [%s]v".format(p)
    }
    errorMessage.map { em =>
      message ++= "<br/>error message: %s".format(em.replaceAll("\n", "\n<br/>"))
    }
    error.map { e =>
      message ++= "<br/>Exception %s stack trace: \n<br/>".format(e.toString())
      message ++= stackTraceHtml
      causeDisplay(e)
    }

    def causeDisplay(e: Throwable): Unit = {
      Option(e.getCause) map { cause =>
        message ++= "<br/>from cause: %s\n<br/>".format(cause.toString)
        message ++= (cause.getStackTrace() mkString "\n<br/> &nbsp; ")
        causeDisplay(cause)
      }
    }
    message.toString()
  }
}
