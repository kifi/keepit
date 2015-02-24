package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import play.api.libs.ws._

import scala.concurrent.{ Future, ExecutionContext }

@ImplementedBy(classOf[SmsCommanderImpl])
trait SmsCommander {
  def sendSms(to: PhoneNumber, message: String): Future[SmsResult]
}

@Singleton
class SmsCommanderImpl @Inject() (
    ws: WebService,
    implicit val ec: ExecutionContext,
    twilioCredentials: TwilioCredentials) extends SmsCommander with Logging {

  def sendSms(to: PhoneNumber, message: String): Future[SmsResult] = {
    ws.url("https://api.twilio.com/2010-04-01/Accounts/" + twilioCredentials.sid + "/Messages.json")
      .withAuth(twilioCredentials.sid, twilioCredentials.token, WSAuthScheme.BASIC)
      .post(Map("To" -> Seq(to.number), "From" -> Seq(twilioCredentials.fromPhoneNumber.number), "Body" -> Seq(message)))
      .map { resp =>
        if (resp.status != 201) {
          log.warn(s"[SmsCommander] Invalid response from Twilio: ${resp.status}, ${resp.body}")
          SmsRemoteFailure
        } else {
          log.info(s"[SmsCommander] Sent SMS successfully! ${resp.body}")
          SmsSuccess
        }
      }
  }
}

class PhoneNumber(digits: String) { // must include country code, in the form 17728795434
  val number = s"+$digits"
}
object PhoneNumber {
  def apply(number: String): PhoneNumber = {
    val digits = "[0-9]+".r.findAllIn(number).mkString("")

    if (digits.startsWith("1")) {
      new PhoneNumber(digits)
    } else if (digits.length == 10 && !digits.startsWith("1")) {
      new PhoneNumber("1" + digits)
    } else {
      new PhoneNumber(digits)
    }
  }
}

sealed trait SmsResult
case object SmsRemoteFailure extends SmsResult
case object SmsSuccess extends SmsResult

case class TwilioCredentials(sid: String, token: String, fromPhoneNumber: PhoneNumber)