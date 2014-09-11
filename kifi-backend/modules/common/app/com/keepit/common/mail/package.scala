package com.keepit.common.mail

import com.keepit.common.db.Id
import com.keepit.model.User
import play.twirl.api.Html

package object template {
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

  // val/def that use tags need to return the Html type so certain characters aren't escaped as HTML entities
  object helpers {

    val title = Tag0(tags.title).toHtml
    val baseUrl = Tag0(tags.baseUrl).toHtml
    val iTunesAppStoreUrl = "https://itunes.apple.com/us/app/kifi/id740232575"
    private val cdnBaseUrl = "https://djty7jcqog9qu.cloudfront.net"
    private val assetBaseUrl = cdnBaseUrl + "/assets/black"

    def assetUrl(path: String) = assetBaseUrl + '/' + path

    def firstName(id: Id[User]) = Tag1(tags.firstName, id).toHtml

    def lastName(id: Id[User]) = Tag1(tags.lastName, id).toHtml

    def fullName(id: Id[User]) = Tag1(tags.fullName, id).toHtml

    def avatarUrl(id: Id[User]) = Tag1(tags.avatarUrl, id).toHtml

    def userExternalId(id: Id[User]) = Tag1(tags.userExternalId, id).toHtml

    def unsubscribeUrl = Tag0(tags.unsubscribeUrl).toHtml

    def unsubscribeUrl(id: Id[User]) = Tag1(tags.unsubscribeUserUrl, id).toHtml

    def unsubscribeUrl(address: EmailAddress) = Tag1(tags.unsubscribeEmailUrl, address).toHtml

    val campaign = Tag0(tags.campaign).toHtml
    private val campaignTagStr = campaign.body

    def toHttpsUrl(url: String) = if (url.startsWith("//")) "https:" + url else url

    def inviteFriendUrl(id: Id[User], index: Int, subtype: String) =
      htmlUrl(s"$baseUrl/invite?friend=${userExternalId(id)}&subtype=$subtype&", "pymk" + index)

    // wrap a url (String) in HTML (so tags aren't escaped)
    private def htmlUrl(url: String, source: String, medium: String = "email"): Html =
      Html(appendUrlUtmCodes(url, campaignTagStr, source, medium))

    // url param must end with a ? or &
    private def appendUrlUtmCodes(url: String, campaign: String, source: String, medium: String = "email"): String = {
      val lastUrlChar = url(url.size - 1)
      require(lastUrlChar == '?' || lastUrlChar == '&', "[appendUrlUtmCodes] url must end with ? or &")
      s"${url}utm_source=$source&utm_medium=$medium&utm_campaign=$campaign"
    }

    val kifiLogoUrl = htmlUrl(s"$baseUrl/?", "headerLogo")
    val kifiFooterUrl = htmlUrl(s"$baseUrl/?", "footerKifiLink")
    val privacyUrl = htmlUrl(s"$baseUrl/privacy?", "footerPrivacy")
    val kifiTwitterUrl = htmlUrl("https://twitter.com/kifi?", "footerTwitter")
    val kifiFacebookUrl = htmlUrl("https://www.facebook.com/kifi42?", "footerFacebook")
  }

}
