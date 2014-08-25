package views.html.email

import com.keepit.social.BasicUser

object helpers {
  val cdnBaseUrl = "https://djty7jcqog9qu.cloudfront.net"

  case class Context(campaign: String, unsubscribeUrl: String, title: String = "Kifi") {
    val privacyUrl = appendUrlUtmCodes("https://www.kifi.com/privacy?", campaign, "footerPrivacy")
    val kifiTwitterUrl = appendUrlUtmCodes("https://twitter.com/kifi?", campaign, "footerTwitter")
    val kifiFacebookUrl = appendUrlUtmCodes("https://www.facebook.com/kifi42?", campaign, "footerFacebook")
    val kifiLogoUrl = appendUrlUtmCodes("https://www.kifi.com/?", campaign, "headerLogo")

    def inviteFriendUrl(user: BasicUser, index: Int) =
      appendUrlUtmCodes(s"https://www.kifi.com/invite?friend=${user.externalId}&", campaign, "pymk" + index)
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
