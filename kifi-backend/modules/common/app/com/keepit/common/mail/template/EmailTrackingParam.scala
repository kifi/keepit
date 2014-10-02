package com.keepit.common.mail.template

import com.keepit.common.logging.Logging
import com.keepit.heimdal.HeimdalContext
import org.apache.commons.codec.binary.Base64
import play.api.data.validation.ValidationError
import play.api.libs.json.{ JsError, JsSuccess, Json, __ }
import play.api.libs.functional.syntax._

case class EmailTrackingParam(
    subAction: Option[String] = None,
    variableComponents: Seq[String] = Seq.empty,
    tips: Seq[EmailTip] = Seq.empty,
    auxiliaryData: Option[HeimdalContext] = None) {

  def encode = EmailTrackingParam.encode(this)
}

object EmailTrackingParam extends Logging {
  val paramName = "dat"

  /*
    ***** README *****
    all changes to existing formats should be backwards compatible,
    if non-backwards-compatible changes are required, the formats need to
    be versioned and when a format is required, the system should detect
    which format to use
  */

  implicit val format = (
    (__ \ "l").formatNullable[String] and
    (__ \ "c").format[Seq[String]] and
    (__ \ "t").format[Seq[EmailTip]] and
    (__ \ "a").formatNullable[HeimdalContext]
  )(EmailTrackingParam.apply, unlift(EmailTrackingParam.unapply))

  def encode(emailLink: EmailTrackingParam): String = {
    val jsVal = Json.toJson(emailLink)
    Base64.encodeBase64String(Json.stringify(jsVal).getBytes)
  }

  def decode(value: String): Either[Seq[ValidationError], EmailTrackingParam] = {
    val jsonStr = new String(Base64.decodeBase64(value))
    val jsVal = Json.parse(jsonStr)
    Json.fromJson(jsVal) match {
      case JsSuccess(param, _) => Right(param)
      case JsError(errors) => Left(errors.flatMap(_._2))
    }
  }
}
