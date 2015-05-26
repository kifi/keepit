package com.keepit.common.oauth

import com.keepit.common.db.State
import com.keepit.common.logging.Logging
import com.keepit.model.{ SocialUserInfoStates, SocialUserInfo }
import com.keepit.social.{ SocialNetworks, SocialId }
import play.api.libs.functional.syntax._
import play.api.libs.json._

// see https://dev.twitter.com/overview/api/users
case class TwitterUserInfo(
    id: Long,
    screenName: String,
    name: String,
    defaultProfile: Boolean,
    defaultProfileImage: Boolean,
    profileImageUrl: java.net.URL,
    followersCount: Long,
    friendsCount: Long) extends Logging {

  val (firstName, lastName) = {
    val splitted = name.split(' ')
    if (splitted.length < 2) (name, "") else (splitted.head, splitted.takeRight(1).head)
  }

  // see https://dev.twitter.com/overview/general/user-profile-images-and-banners
  private val profileImagePattern = """([^\s]+)\_normal\.([^\s]+)$""".r

  lazy val pictureUrl: Option[java.net.URL] =
    if (defaultProfileImage) None
    else {
      val s = profileImageUrl.toString
      val processed = s match {
        case profileImagePattern(orig, ext) =>
          log.info(s"[pictureUrl] matched orig=$orig ext=$ext")
          s"$orig.$ext"
        case _ => s
      }
      Some(new java.net.URL(processed))
    }

  lazy val profileUrl: java.net.URL = new java.net.URL(s"https://www.twitter.com/$screenName")

}

object TwitterUserInfo {
  def toSocialUserInfo(tui: TwitterUserInfo, state: State[SocialUserInfo] = SocialUserInfoStates.FETCHED_USING_FRIEND): SocialUserInfo = {
    val id = tui.id.toString.trim
    if (id.isEmpty) throw new Exception(s"empty social id for $tui")
    SocialUserInfo(
      fullName = tui.name,
      socialId = SocialId(id),
      networkType = SocialNetworks.TWITTER,
      state = state,
      pictureUrl = tui.pictureUrl.map(_.toString),
      profileUrl = Some(tui.profileUrl.toString),
      username = Some(tui.screenName)
    )
  }

  def toUserProfileInfo(tui: TwitterUserInfo): UserProfileInfo = {
    UserProfileInfo(
      providerId = ProviderIds.Twitter,
      userId = ProviderUserId(tui.id.toString),
      name = tui.name,
      emailOpt = None,
      firstNameOpt = Some(tui.firstName),
      lastNameOpt = if (tui.lastName.isEmpty) None else Some(tui.lastName),
      handle = Some(UserHandle(tui.screenName)),
      pictureUrl = tui.pictureUrl,
      profileUrl = Some(tui.profileUrl)
    )
  }

  implicit val format = (
    (__ \ 'id).format[Long] and
    (__ \ 'screen_name).format[String] and
    (__ \ 'name).format[String] and
    (__ \ 'default_profile).format[Boolean] and
    (__ \ 'default_profile_image).format[Boolean] and
    (__ \ 'profile_image_url_https).format[String].inmap({ s: String => new java.net.URL(s) }, { url: java.net.URL => url.toString }) and
    (__ \ 'followers_count).format[Long] and
    (__ \ 'friends_count).format[Long]
  )(TwitterUserInfo.apply _, unlift(TwitterUserInfo.unapply))
}

