package com.keepit.common.healthcheck

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.common.strings._
import org.joda.time.DateTime

import java.io._
import java.net._
import java.security.MessageDigest

import org.apache.commons.codec.binary.Base64

import scala.xml._

import play.api.mvc._
import play.api.libs.ws.WS.WSRequestHolder

case class AirbrakeErrorSignature(value: String) extends AnyVal
class DefaultAirbrakeException extends Exception

case class AirbrakeError(
    exception: Throwable = new DefaultAirbrakeException(),
    message: Option[String] = None,
    url: Option[String] = None,
    params: Map[String, Seq[String]] = Map(),
    method: Option[String] = None,
    headers: Map[String, Seq[String]] = Map(),
    id: ExternalId[AirbrakeError] = ExternalId(),
    createdAt: DateTime = currentDateTime,
    details: Option[String] = None) {

  lazy val trimmedMessage = message.map(_.toString.abbreviate(AirbrakeError.MaxMessageSize))
  override def toString(): String = {
    s"${super.toString()}\n${exception.getStackTrace mkString "\nat \t"}"
  }

  lazy val rootException: Throwable = findRootException(exception)
  private def findRootException(throwable: Throwable): Throwable =
    Option(exception.getCause()).map(c => findRootException(c)).getOrElse(throwable)

  private val Max8M = 8 * 1024 * 1024

  lazy val signature: AirbrakeErrorSignature = {
    val permText: String =
      causeStacktraceHead(4).getOrElse(message.map(_.take(Max8M)).getOrElse("")) +
        url.getOrElse("") +
        method.getOrElse("")
    val binaryHash = MessageDigest.getInstance("MD5").digest(permText)
    AirbrakeErrorSignature(new String(new Base64().encode(binaryHash), UTF8))
  }

  private def causeStacktraceHead(depth: Int, throwable: Option[Throwable] = Some(exception)): Option[String] = throwable match {
    case None => None
    case Some(t) =>
      causeStacktraceHead(depth, Option(t.getCause)) match {
        case Some(msg) => Some(msg)
        case None => Some(t.getStackTrace().take(depth).map(e => e.getClassName + e.getLineNumber).mkString(":"))
      }
  }

  def toHtml: String = {
    val message = new StringBuilder(s"$createdAt: [$id]")
    method.map { m =>
      message ++= s"""<br/><b>http method</b> <span style="color:blue; font-size: 13px; font-style: italic;">[$m]</span>"""
    }
    url.map { p =>
      message ++= s"""<br/><b>url</b> <span style="color:blue; font-size: 13px; font-style: italic;">[$p]</span>"""
    }
    this.message.map { em =>
      message ++= s"""<br/><b>error message</b> <span style="color:red; font-size: 13px; font-style: italic;">[${em.replaceAll("\n", "\n<br/>")}]</span>"""
    }
    params.map { case (name, values) =>
      message ++= s"\n* Param:\n$name = ${values mkString ","}"
    }
    headers.map { case (name, values) =>
      message ++= s"\n* Header:\n$name = ${values mkString ","}"
    }

    message ++= "<br/><b>Exception stack trace:\n</b><br/>"
    message ++= htmlStackTrace

    message.toString()
  }

  private lazy val htmlStackTrace: String = {
    def causeString(throwableOptions: Option[Throwable]): String = throwableOptions match {
      case None => "--"
      case Some(t) if (t.getStackTrace().exists({ e =>
        e.getClassName.contains("play.api.Application") && e.getMethodName.contains("handleError")
      })) => causeString(Option(t.getCause))
      case Some(t) =>
        s"""<span style="color:red; font-size: 13px; font-style: bold;">Cause:</span><br/>
            <span style="color:green; font-size: 16px; font-style: bold;">${t.toString}</span>\n<br/>
            ${(t.getStackTrace() map formatStackElementHtml mkString "\n<br/> ")}<br/>
            ${causeString(Option(t.getCause))}"""
    }
    causeString(Some(exception))
  }

  private def formatStackElementHtml(e: StackTraceElement) = {
    def className(klazz: String) =
      if (klazz.contains("com.keepit")) s"""<span style="color:blue; font-size: 13px; ">$klazz</span>"""
      else s"""<span style="color:black; font-size: 13px;">$klazz</span>"""
    s"""${className(ignoreAnonfun(e.getClassName))}.<span style="color:black; font-size: 13px; font-style: italic;">${e.getMethodName}</span><span style="color:grey; font-size: 10px;">(${e.getFileName}:${e.getLineNumber})</span>"""
  }

  private def ignoreAnonfun(klazz: String) = klazz.replaceAll("""\$\$anonfun.*""", "[a]")

  lazy val titleHtml: String = {
    def causeString(throwableOptions: Option[Throwable]): String = throwableOptions match {
      case None => "[No Cause]"
      case Some(t) => s"""${t.getClass.getName}: ${t.getMessage}\n<br/> &nbsp; <span style="color:red; font-size: 13px; font-style: bold;">Cause:</span> ${causeString(Option(t.getCause))}"""
    }
    s"${message.getOrElse("Exception:")} ${causeString(Some(exception))}"
  }

}

object AirbrakeError {
  val MaxMessageSize = 10 * 1024 //10KB
  def incoming(request: RequestHeader, exception: Throwable = new DefaultAirbrakeException(), message: String = ""): AirbrakeError =
    new AirbrakeError(
          exception = exception,
          message = if (message.trim.isEmpty) None else Some(message.abbreviate(MaxMessageSize)),
          url = Some(request.uri.abbreviate(MaxMessageSize)),
          params = request.queryString,
          method = Some(request.method),
          headers = request.headers.toMap)

  def outgoing(request: WSRequestHolder, exception: Throwable = new DefaultAirbrakeException(), message: String = ""): AirbrakeError = {
    new AirbrakeError(
          exception = exception,
          message = if (message.trim.isEmpty) None else Some(message.abbreviate(MaxMessageSize)),
          url = Some(request.url.abbreviate(MaxMessageSize)),
          params = request.queryString,
          headers = request.headers)
  }

  implicit def error(t: Throwable): AirbrakeError = AirbrakeError(t)
}
