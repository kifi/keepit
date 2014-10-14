package com.keepit.common.mail.template

import com.keepit.common.logging.Logging
import com.keepit.heimdal.SimpleContextData
import play.api.libs.json.{ JsSuccess, JsError, JsResult, JsString, Json, JsValue, Format }

sealed trait EmailTip {
  val name: String
}

object EmailTip {
  implicit val format = new Format[EmailTip] {
    def writes(o: EmailTip) = Json.toJson(o.name)
    def reads(json: JsValue) =
      json.asOpt[JsString].flatMap(v => apply(v.value)).fold[JsResult[EmailTip]](
        JsError(s"existing EmailTip could not be found from $json")
      )(JsSuccess(_))
  }

  private[EmailTip] case class EmailTipImpl(name: String) extends EmailTip

  // EmailTip.value is kept short b/c it is encoded in URLs;
  // of course, each value should be unique
  object FriendRecommendations extends EmailTipImpl("PYMK")
  object ConnectFacebook extends EmailTipImpl("cFB")
  object ConnectLinkedIn extends EmailTipImpl("cLI")
  object ImportGmailContacts extends EmailTipImpl("gmail")
  object InstallExtension extends EmailTipImpl("ext")
  object KeepFromEmail extends EmailTipImpl("keepFromEmail")
  object BookmarkImport extends EmailTipImpl("bmImport")

  val ALL: Seq[EmailTip] = FriendRecommendations :: ConnectFacebook :: ConnectLinkedIn ::
    KeepFromEmail :: BookmarkImport :: Nil

  def apply(name: String): Option[EmailTip] = ALL.find(_.name == name)

  implicit def toContextData(tip: EmailTip): SimpleContextData =
    SimpleContextData.toContextStringData(tip.name)

  implicit def fromContextData(ctx: SimpleContextData): Option[EmailTip] =
    SimpleContextData.fromContextStringData(ctx) flatMap EmailTip.apply
}

trait TipTemplate extends Logging
