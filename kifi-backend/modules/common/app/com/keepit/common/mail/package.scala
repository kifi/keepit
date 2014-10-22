package com.keepit.common.mail

import com.google.common.html.HtmlEscapers
import com.keepit.common.db.Id
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.{ Library, User }
import play.twirl.api.Html

object KifiMobileAppLinkFlag {
  val key = "kma"
  val value = "1"
  val arg = s"$key=$value"
  def apply(url: String): String = if (url.contains(arg)) {
    url
  } else if (url.contains("?")) {
    url + "&" + arg
  } else {
    url + "?" + arg
  }
}

package object template {
  object tags {
    val firstName = TagLabel("firstName")
    val lastName = TagLabel("lastName")
    val fullName = TagLabel("fullName")
    val avatarUrl = TagLabel("avatarUrl")
    val libraryName = TagLabel("libraryName")
    val unsubscribeUrl = TagLabel("unsubscribeUrl")
    val unsubscribeUserUrl = TagLabel("unsubscribeUserUrl")
    val unsubscribeEmailUrl = TagLabel("unsubscribeEmailUrl")
    val userExternalId = TagLabel("userExternalId")
    val campaign = TagLabel("campaign")
    val parentCategory = TagLabel("parentCategory")
    val title = TagLabel("title")
    val baseUrl = TagLabel("baseUrl")
    val trackingParam = TagLabel("trackingParam")
    val footerHtml = TagLabel("footer")
  }

  // val/def that use tags need to return the Html type so certain characters aren't escaped as HTML entities
  object helpers {
    private val htmlEscape = HtmlEscapers.htmlEscaper().asFunction()

    val title = Tag0(tags.title).toHtml
    val baseUrl = Tag0(tags.baseUrl).toHtml
    val iTunesAppStoreUrl = "https://itunes.apple.com/us/app/kifi/id740232575"
    private val cdnBaseUrl = "https://djty7jcqog9qu.cloudfront.net"
    private val assetBaseUrl = cdnBaseUrl + "/assets/black"

    val footerHtml = Tag0(tags.footerHtml).toHtml

    def assetUrl(path: String) = assetBaseUrl + '/' + path

    def firstName(id: Id[User]) = Tag1(tags.firstName, id).toHtml

    def lastName(id: Id[User]) = Tag1(tags.lastName, id).toHtml

    def fullName(id: Id[User]) = Tag1(tags.fullName, id).toHtml

    def avatarUrl(id: Id[User]) = Tag1(tags.avatarUrl, id).toHtml

    def userExternalId(id: Id[User]) = Tag1(tags.userExternalId, id).toHtml

    def libraryName(id: Id[Library]) = Tag1(tags.libraryName, id).toHtml

    val unsubscribeUrl = Tag0(tags.unsubscribeUrl).toHtml

    def unsubscribeUrl(id: Id[User]) = Tag1(tags.unsubscribeUserUrl, id).toHtml

    def unsubscribeUrl(address: EmailAddress) = Tag1(tags.unsubscribeEmailUrl, address).toHtml

    def trackingParam(content: String, auxData: Option[HeimdalContext] = None) =
      Tag2(tags.trackingParam, content, auxData).toHtml

    val campaign = Tag0(tags.campaign).toHtml
    private val campaignTagStr = campaign.body

    val parentCategory = Tag0(tags.parentCategory).toHtml
    private val parentCategoryTagStr = parentCategory.body

    def toHttpsUrl(url: String) = if (url.startsWith("//")) "https:" + url else url

    def findMoreFriendsUrl(content: String) = htmlUrl(s"$baseUrl/friends?", content, openInAppIfMobile = true)

    def acceptFriendUrl(id: Id[User], content: String) = htmlUrl(s"$baseUrl/friends?friend=${userExternalId(id)}&", content, openInAppIfMobile = true)

    private def connectNetworkUrl(network: String, content: String): Html = Html {
      s"$baseUrl/link/$network?${EmailTrackingParam.paramName}=${trackingParam(content)}"
    }

    def connectFacebookUrl(content: String) = connectNetworkUrl("facebook", content)
    def connectLinkedInUrl(content: String) = connectNetworkUrl("linkedin", content)

    def inviteContactUrl(id: Id[User], content: String) =
      htmlUrl(s"$baseUrl/invite?friend=${userExternalId(id)}&subtype=contactJoined&", content, openInAppIfMobile = true)

    def inviteFriendUrl(id: Id[User], index: Int, subtype: String) =
      htmlUrl(s"$baseUrl/invite?friend=${userExternalId(id)}&subtype=$subtype&", "pymk" + index, openInAppIfMobile = true)

    // wrap a url (String) in HTML (so tags aren't escaped)
    def htmlUrl(url: String, content: String, openInAppIfMobile: Boolean): Html =
      Html(appendTrackingParams(url = url, content = content, openInAppIfMobile = openInAppIfMobile))

    // url param must end with a ? or &
    private def appendTrackingParams(url: String, content: String, campaign: String = campaignTagStr,
      medium: String = "email", source: String = parentCategoryTagStr, openInAppIfMobile: Boolean): String = {
      val lastUrlChar = url(url.size - 1)
      require(lastUrlChar == '?' || lastUrlChar == '&', "[appendTrackingParams] url must end with ? or &")
      val openInAppIfMobileDirective = if (openInAppIfMobile) KifiMobileAppLinkFlag.arg else ""
      s"${url}utm_source=$source&utm_medium=$medium&utm_campaign=$campaign&utm_content=$content" +
        s"&${EmailTrackingParam.paramName}=${trackingParam(content)}&$openInAppIfMobileDirective"
    }

    def kifiUrl(content: String = "unknown") = htmlUrl(s"$baseUrl/?", content, openInAppIfMobile = true)

    val kifiAddress = "883 N Shoreline Blvd, Mountain View, CA 94043, USA"
    val kifiLogoUrl = kifiUrl("headerLogo")
    val kifiFooterUrl = kifiUrl("footerKifiLink")
    val privacyUrl = htmlUrl(s"$baseUrl/privacy?", "footerPrivacy", openInAppIfMobile = false)
    val kifiTwitterUrl = htmlUrl("https://twitter.com/kifi?", "footerTwitter", openInAppIfMobile = false)
    val kifiFacebookUrl = htmlUrl("https://www.facebook.com/kifi42?", "footerFacebook", openInAppIfMobile = false)

    val kifiChromeExtensionUrl =
      "https://chrome.google.com/webstore/detail/kifi/fpjooibalklfinmkiodaamcckfbcjhin"

    // prevents email clients from auto-linking text like "kifi.com"
    def escapeAutoLinkText(str: String): Html = {
      val lastPeriodIdx = str.lastIndexOf('.')
      Html {
        // to prevent Play from auto-escaping our string we wrap the string in Html,
        // but for safety, escape every part of the return value except the entity we insert
        if (lastPeriodIdx > 0 && lastPeriodIdx < str.length - 1) {
          val (before, after) = str.splitAt(lastPeriodIdx)
          htmlEscape(before) + "&#8203;" + htmlEscape(after)
        } else htmlEscape(str)
      }
    }
  }

}
