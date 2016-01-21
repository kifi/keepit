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
    profileImageUrl: String,
    followersCount: Long,
    friendsCount: Long) extends Logging {

  val (firstName, lastName) = {
    val splitted = name.split(' ')
    if (splitted.length < 2) (name, "") else (splitted.head, splitted.takeRight(1).head)
  }

  // see https://dev.twitter.com/overview/general/user-profile-images-and-banners
  private val profileImagePattern = """([^\s]+)\_normal\.([^\s]+)$""".r

  lazy val pictureUrl: Option[String] =
    if (defaultProfileImage) None
    else {
      val s = profileImageUrl.toString
      val processed = s match {
        case profileImagePattern(orig, ext) =>
          s"$orig.$ext"
        case _ => s
      }
      Some(processed)
    }

  lazy val profileUrl: String = s"https://www.twitter.com/$screenName"

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
      pictureUrl = tui.pictureUrl,
      profileUrl = Some(tui.profileUrl),
      username = Some(tui.screenName)
    )
  }

  implicit val format = (
    (__ \ 'id).format[Long] and
    (__ \ 'screen_name).format[String] and
    (__ \ 'name).format[String] and
    (__ \ 'default_profile).format[Boolean] and
    (__ \ 'default_profile_image).format[Boolean] and
    (__ \ 'profile_image_url_https).format[String] and
    (__ \ 'followers_count).format[Long] and
    (__ \ 'friends_count).format[Long]
  )(TwitterUserInfo.apply _, unlift(TwitterUserInfo.unapply))
}

