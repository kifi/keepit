package com.keepit.social

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.{ S3ExternalIdImageStore, S3ImageConfig }
import com.keepit.common.strings.ValidLong
import com.keepit.model._
import com.keepit.slack.models.{ SlackMessage, SlackUsername, SlackUserId, SlackTeamId }
import com.keepit.social.twitter.{ RawTweet, TwitterUserId }
import play.api.libs.json.{ OWrites, Json, Writes }
import com.keepit.common.core.jsObjectExtensionOps

object ImageUrls {
  val SLACK_LOGO = "https://djty7jcqog9qu.cloudfront.net/oa/98c4c6dc6bf8aeca952d2316df5b242b_200x200-0x0-200x200_cs.png"
  val KIFI_LOGO = "https://d1dwdv9wd966qu.cloudfront.net/img/favicon64x64.7cc6dd4.png"
  val TWITTER_LOGO = "https://d1dwdv9wd966qu.cloudfront.net/img/twitter_64x64.png"
}

sealed abstract class Author(val kind: AuthorKind)
object Author {
  case class KifiUser(userId: Id[User]) extends Author(AuthorKind.Kifi)
  case class SlackUser(teamId: SlackTeamId, userId: SlackUserId) extends Author(AuthorKind.Slack)
  case class TwitterUser(userId: TwitterUserId) extends Author(AuthorKind.Twitter)
  def fromSource(attr: RawSourceAttribution): Author = attr match {
    case RawTwitterAttribution(tweet) => TwitterUser(tweet.user.id)
    case RawSlackAttribution(msg, teamId) => SlackUser(teamId, msg.userId)
  }

  def toIndexableString(author: Author): String = {
    val key = author match {
      case KifiUser(userId) => userId.id.toString
      case SlackUser(teamId, userId) => s"${teamId.value}_${userId.value}"
      case TwitterUser(userId) => userId.id.toString
    }
    author.kind.value + "|" + key
  }

  private val kifi = """^kifi\|(.+)$""".r
  private val slack = """^slack\|(.+)_(.+)$""".r
  private val twitter = """^twitter\|(.+)$""".r
  def fromIndexableString(str: String): Author = str match {
    case kifi(ValidLong(id)) => KifiUser(Id[User](id))
    case slack(teamId, userId) => SlackUser(SlackTeamId(teamId), SlackUserId(userId))
    case twitter(ValidLong(id)) => TwitterUser(TwitterUserId(id))
  }
}

sealed abstract class AuthorKind(val value: String)
object AuthorKind {
  case object Kifi extends AuthorKind("kifi")
  case object Slack extends AuthorKind("slack")
  case object Twitter extends AuthorKind("twitter")
}

sealed abstract class BasicAuthor(val kind: AuthorKind) {
  // Minimum required fields for any basic author
  def id: String
  def name: String
  def picture: String
}

object BasicAuthor {
  case object Fake extends BasicAuthor(AuthorKind.Kifi) {
    override def id = "42424242-4242-4242-424242424242"
    override def name = "fake"
    override def picture = ImageUrls.KIFI_LOGO
  }
  case class KifiUser(user: BasicUser)(implicit s3: S3ExternalIdImageStore) extends BasicAuthor(AuthorKind.Kifi) {
    override def id = user.externalId.id
    override def name = user.fullName
    override def picture = s3.avatarUrlByUser(user)
    def url = user.path.absolute
  }
  case class SlackUser(msg: BasicSlackMessage) extends BasicAuthor(AuthorKind.Slack) {
    override def id = msg.userId.value
    override def name = msg.username.value
    override def picture = ImageUrls.SLACK_LOGO
    def url = msg.permalink
  }
  case class TwitterUser(tweet: BasicTweet) extends BasicAuthor(AuthorKind.Twitter) {
    override def id = tweet.user.id.id.toString
    override def name = tweet.user.name
    override def picture = ImageUrls.TWITTER_LOGO
    def url = tweet.permalink
  }

  def fromSource(source: SourceAttribution): BasicAuthor = source match {
    case SlackAttribution(msg) => SlackUser(msg)
    case TwitterAttribution(tweet) => TwitterUser(tweet)
  }

  implicit val writes: Writes[BasicAuthor] = OWrites { ba =>
    val basicFields = Json.obj(
      "kind" -> ba.kind.value,
      "id" -> ba.id,
      "name" -> ba.name,
      "picture" -> ba.picture
    )
    val extraFields = ba match {
      case Fake => Json.obj()
      case KifiUser(user) => Json.obj("url" -> user.path.absolute)
      case SlackUser(msg) => Json.obj("url" -> msg.permalink)
      case TwitterUser(tweet) => Json.obj("url" -> tweet.permalink)
    }
    basicFields ++ extraFields
  }
}
