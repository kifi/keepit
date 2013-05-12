package com.keepit.common.healthcheck

import java.security.MessageDigest

import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime

import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.Healthcheck._
import com.keepit.common.time._

case class HealthcheckErrorSignature(value: String) extends AnyVal

// This allows you to separate the message from any user-specific info we're collecting
case class ErrorMessage(message: String, additionalInfo: Option[String] = None) {
  override def toString = (Seq(message) ++ additionalInfo).mkString("\n")
}

object ErrorMessage {
  implicit def toErrorMessage(message: String): ErrorMessage = ErrorMessage(message)
  implicit def fromErrorMessage(message: ErrorMessage): String = message.toString
}

case class HealthcheckError(
  error: Option[Throwable] = None,
  method: Option[String] = None,
  path: Option[String] = None,
  callType: CallType,
  errorMessage: Option[ErrorMessage] = None,
  id: ExternalId[HealthcheckError] = ExternalId(),
  createdAt: DateTime = currentDateTime) {

  lazy val signature: HealthcheckErrorSignature = {
    val permText: String =
      causeStacktraceHead(4).getOrElse(errorMessage.map(_.message).getOrElse("")) +
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

  private def formatStackElementHtml(e: StackTraceElement) = {
    def className(klazz: String) =
      if (klazz.contains("com.keepit")) s"""<span style="color:blue; font-size: 13px; ">$klazz</span>"""
      else s"""<span style="color:black; font-size: 13px;">$klazz</span>"""
    s"""${className(ignoreAnonfun(e.getClassName))}.<span style="color:black; font-size: 13px; font-style: italic;">${e.getMethodName}</span><span style="color:grey; font-size: 10px;">(${e.getFileName}:${e.getLineNumber})</span>"""
  }

  private def ignoreAnonfun(klazz: String) = klazz.replaceAll("""\$\$anonfun.*""", "[a]")

  private def formatStackElementText(e: StackTraceElement) = {
    s"${if(e.getClassName.startsWith("com.keepit")) "*" else " "}${e.getFileName}[${e.getLineNumber}]${e.getMethodName}"
  }

  def stackTrace(html: Boolean): String = {
    def causeString(throwableOptions: Option[Throwable]): String = throwableOptions match {
      case None => "--"
      case Some(t) if (t.getStackTrace().exists({ e =>
        e.getClassName.contains("play.api.Application") && e.getMethodName.contains("handleError")
      })) => causeString(Option(t.getCause))
      case Some(t) =>
        if (html)
          s"""<span style="color:red; font-size: 13px; font-style: bold;">Cause:</span><br/>
              <span style="color:green; font-size: 16px; font-style: bold;">${t.toString}</span>\n<br/>
              ${(t.getStackTrace() map formatStackElementHtml mkString "\n<br/> ")}<br/>
              ${causeString(Option(t.getCause))}"""
        else
          s"Cause: ${t.toString}" +
          s" ${(t.getStackTrace() map formatStackElementText mkString "\n ")}" +
          s"${causeString(Option(t.getCause))}"
    }
    causeString(error)
  }

  lazy val subjectName: String = {
    def cause(t: Throwable): Throwable = Option(t.getCause) match {
      case None => t
      case Some(c) => cause(c)
    }

    def displayMessage(str: String) =
      ExternalId.UUIDPattern.replaceAllIn(Option(str).getOrElse(""), "********-****-****-****-************")
        .replaceAll("\\d", "*").take(60)
    error match {
      case None =>
        val message = errorMessage.map(_.message).getOrElse(path.getOrElse(callType.toString()))
        displayMessage(message)
      case Some(t) =>
        val source = cause(t)
        val shortMessage = displayMessage(source.getMessage)
        s"${source.getClass.toString} : $shortMessage..."
    }
  }

  lazy val titleHtml: String = {
    def causeString(throwableOptions: Option[Throwable]): String = throwableOptions match {
      case None => "[No Cause]"
      case Some(t) => s"""${t.getClass.getName}: ${t.getMessage}\n<br/> &nbsp; <span style="color:red; font-size: 13px; font-style: bold;">Cause:</span> ${causeString(Option(t.getCause))}"""
    }
    (error map (e => causeString(error))).getOrElse(errorMessage.getOrElse(this).toString)
  }

  lazy val titleText: String = {
    def causeString(throwableOptions: Option[Throwable]): String = throwableOptions match {
      case None => "[No Cause]"
      case Some(t) => s"""${t.getClass.getName}: ${t.getMessage}\n* Cause: ${causeString(Option(t.getCause))}"""
    }
    (error map (e => causeString(error))).getOrElse(errorMessage.getOrElse(this).toString)
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
      message ++= stackTrace(true)
    }
    message.toString()
  }

  def toText: String = {
    val message = new StringBuilder(s"* Id: [$id]\n* Call type: $callType\n* Created at: $createdAt")
    method.map { m =>
      message ++= s"\n* Http method: [$m]"
    }
    path.map { p =>
      message ++= s"\n* Path: [$p]"
    }
    errorMessage.map { em =>
      message ++= s"\n* Error message:\n$em"
    }
    error.map { e =>
      message ++= "\n\nException stack trace\n----------------------\n"
      message ++= stackTrace(false)
    }
    message.toString()
  }
}
