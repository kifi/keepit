package com.keepit.social

import com.keepit.common.db.Id
import com.keepit.common.strings.ValidLong
import com.keepit.model._
import com.keepit.slack.models.{ SlackUserId, SlackTeamId }
import com.keepit.social.twitter.TwitterUserId
import play.api.libs.json.{ Json, Writes }

sealed trait Author
object Author {
  case class KifiUser(userId: Id[User]) extends Author
  case class SlackUser(userId: SlackUserId) extends Author
  case class TwitterUser(userId: TwitterUserId) extends Author
  def fromSource(attr: RawSourceAttribution): Author = attr match {
    case RawTwitterAttribution(tweet) => TwitterUser(tweet.user.id)
    case RawSlackAttribution(msg, teamId) => SlackUser(msg.userId)
  }

  def toIndexableString(author: Author): String = author match {
    case KifiUser(userId) => s"kifi|${userId.id}"
    case SlackUser(userId) => s"slack|${userId.value}"
    case TwitterUser(userId) => s"twitter|${userId.id}"
  }

  private val kifi = """^kifi\|(.+)$""".r
  private val slack = """^slack\|(.+)$""".r
  private val twitter = """^twitter\|(.+)$""".r
  def fromIndexableString(str: String): Author = str match {
    case kifi(ValidLong(id)) => KifiUser(Id[User](id))
    case slack(userId) => SlackUser(SlackUserId(userId))
    case twitter(ValidLong(id)) => TwitterUser(TwitterUserId(id))
  }
}

case class BasicAuthor(
  displayName: String,
  picture: Option[String],
  url: Option[String])

object BasicAuthor {
  implicit val writes: Writes[BasicAuthor] = Json.writes[BasicAuthor]
  val FAKE = BasicAuthor(displayName = "you", picture = None, url = None)
  def fromAttribution(attr: SourceAttribution): BasicAuthor = {
    attr match {
      case TwitterAttribution(tweet) => BasicAuthor(displayName = tweet.user.name, picture = Some(tweet.user.profileImageUrlHttps), url = None)
      case SlackAttribution(msg) => BasicAuthor(displayName = msg.username.value, picture = None, url = None)
    }
  }
  def fromUser(user: BasicUser) = BasicAuthor(displayName = user.fullName, picture = Some(user.pictureName), url = Some(user.path.absolute))
}
