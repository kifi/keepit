package views.html.email

import com.keepit.common.db.Id
import com.keepit.common.mail.{ ElectronicMail, ElectronicMailCategory }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.User
import com.keepit.social.BasicUser

object helpers {
  val cdnBaseUrl = "https://djty7jcqog9qu.cloudfront.net"

  case class Context(campaign: String, unsubscribeUrl: Option[String], title: String = "Kifi", protected val config: FortyTwoConfig) {
    val baseUrl = config.applicationBaseUrl
    val privacyUrl = appendUrlUtmCodes(s"${baseUrl}/privacy?", campaign, "footerPrivacy")
    val kifiTwitterUrl = appendUrlUtmCodes("https://twitter.com/kifi?", campaign, "footerTwitter")
    val kifiFacebookUrl = appendUrlUtmCodes("https://www.facebook.com/kifi42?", campaign, "footerFacebook")
    val kifiLogoUrl = appendUrlUtmCodes(s"${baseUrl}/?", campaign, "headerLogo")

    def inviteFriendUrl(user: BasicUser, index: Int, subtype: String) =
      appendUrlUtmCodes(s"$baseUrl/invite?friend=${user.externalId}&subtype=$subtype&", campaign, "pymk" + index)
  }

  val iTunesAppStoreUrl = "https://itunes.apple.com/us/app/kifi/id740232575"

  // helpers for the black email theme
  object black {
    val assetBaseUrl = cdnBaseUrl + "/assets/black"

    def assetUrl(path: String) = assetBaseUrl + '/' + path
  }

  // url param must end with a ? or &
  def appendUrlUtmCodes(url: String, campaign: String, source: String, medium: String = "email"): String =
    s"${url}utm_source=${source}&utm_medium=${medium}&utm_campaign=${campaign}"
}
