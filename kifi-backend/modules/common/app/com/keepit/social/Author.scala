package com.keepit.social

import com.keepit.common.db.Id
import com.keepit.model.{ BasicSlackMessage, BasicTweet, User }
import com.keepit.slack.models.{ SlackTeamId, SlackUserId }
import com.keepit.social.twitter.RawTweet

case class BasicAuthor(
  displayName: String,
  picture: Option[String],
  url: Option[String])

object BasicAuthor {
  val FAKE = BasicAuthor(displayName = "you", picture = None, url = None)
  def fromTwitter(user: RawTweet.User) = BasicAuthor(displayName = user.name, picture = Some(user.profileImageUrlHttps), url = None)
  def fromSlack(user: BasicSlackMessage.User) = BasicAuthor(displayName = user.name.value, picture = None, url = None)
  def fromUser(user: BasicUser) = BasicAuthor(displayName = s"${user.firstName} ${user.lastName}", picture = Some(user.pictureName), url = Some(user.path.absolute))
}
