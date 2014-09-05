package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import play.api.libs.json._
import play.api.templates.Html

import scala.util.matching.Regex

object Email {

  object TagLabel {
    implicit val format = new Format[TagLabel] {
      def reads(js: JsValue) = JsSuccess(TagLabel(js.as[JsString].value))

      def writes(o: TagLabel) = JsString(o.value)
    }
  }

  case class TagLabel(value: String) extends AnyVal

  case class TagWrapper(label: TagLabel, args: Seq[JsValue])

  object TagWrapper {
    implicit val reads = new Reads[TagWrapper] {
      def reads(jsVal: JsValue) = {
        val jsValues = jsVal.as[JsArray].value
        val label = jsValues.head.as[TagLabel]
        val tagArgs = jsValues.tail
        JsSuccess(TagWrapper(label, tagArgs))
      }
    }
  }

  trait Tag {
    def label: TagLabel

    def args: Seq[JsValue]

    override def toString =
      tagLeftDelim + JsArray(Seq(JsString(label.value)) ++ args).toString() + tagRightDelim
  }

  case class Tag0(label: TagLabel) extends Tag {
    def args = Seq.empty
  }

  case class Tag1[S](label: TagLabel, arg: S)(implicit val writer: Writes[S]) extends Tag {
    def args = Seq(writer.writes(arg))
  }

  case class Tag3[S, T, U](label: TagLabel, arg0: S, arg1: T, arg2: U)(implicit val writerS: Writes[S], writerT: Writes[T], writerU: Writes[U]) extends Tag {
    def args = Seq(writerS.writes(arg0), writerT.writes(arg1), writerU.writes(arg2))
  }

  object tags {
    val firstName = TagLabel("firstName")
    val lastName = TagLabel("lastName")
    val fullName = TagLabel("fullName")
    val avatarUrl = TagLabel("avatarUrl")
    val unsubscribeUrl = TagLabel("unsubscribeUrl")
    val unsubscribeUserUrl = TagLabel("unsubscribeUserUrl")
    val unsubscribeEmailUrl = TagLabel("unsubscribeEmailUrl")
    val userExternalId = TagLabel("userExternalId")
    val campaign = TagLabel("campaign")
    val title = TagLabel("title")
    val baseUrl = TagLabel("baseUrl")
  }

  val tagLeftDelim: String = "<% "
  val tagRightDelim: String = " %>"
  val tagRegex = (Regex.quoteReplacement(tagLeftDelim) + "(.*?)" + Regex.quoteReplacement(tagRightDelim)).r

  object placeholders {
    def firstName(id: Id[User]) = Tag1(tags.firstName, id)

    def lastName(id: Id[User]) = Tag1(tags.lastName, id)

    def fullName(id: Id[User]) = Tag1(tags.fullName, id)

    def avatarUrl(id: Id[User]) = Tag1(tags.avatarUrl, id)

    def userExternalId(id: Id[User]) = Tag1(tags.userExternalId, id)

    def unsubscribeUrl = Tag0(tags.unsubscribeUrl)

    def unsubscribeUrl(id: Id[User]) = Tag1(tags.unsubscribeUserUrl, id)

    def unsubscribeUrl(address: EmailAddress) = Tag1(tags.unsubscribeEmailUrl, address)

    def campaign = Tag0(tags.campaign)

    def title = Tag0(tags.title)

    def baseUrl = Tag0(tags.baseUrl)
  }

  object helpers {
    import placeholders._

    val cdnBaseUrl = "https://djty7jcqog9qu.cloudfront.net"
    val iTunesAppStoreUrl = "https://itunes.apple.com/us/app/kifi/id740232575"

    private val campaignTagStr = campaign.toString()

    val kifiLogoUrl = htmlUrl(s"$baseUrl/?", "headerLogo")
    val privacyUrl = htmlUrl(s"$baseUrl/privacy?", "footerPrivacy")
    val kifiTwitterUrl = htmlUrl("https://twitter.com/kifi?", "footerTwitter")
    val kifiFacebookUrl = htmlUrl("https://www.facebook.com/kifi42?", "footerFacebook")

    def inviteFriendUrl(id: Id[User], index: Int, subtype: String) =
      htmlUrl(s"$baseUrl/invite?friend=${userExternalId(id)}&subtype=$subtype&", "pymk" + index)

    private def htmlUrl(url: String, source: String, medium: String = "email"): Html =
      Html(appendUrlUtmCodes(url, campaignTagStr, source, medium))

    // url param must end with a ? or &
    private def appendUrlUtmCodes(url: String, campaign: String, source: String, medium: String = "email"): String =
      s"${url}utm_source=$source&utm_medium=$medium&utm_campaign=$campaign"
  }

  // helpers for the black email theme
  object black {
    val assetBaseUrl = helpers.cdnBaseUrl + "/assets/black"

    def assetUrl(path: String) = assetBaseUrl + '/' + path
  }
}
