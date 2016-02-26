package com.keepit.social

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.path.Path
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.strings.ValidLong
import com.keepit.model._
import com.keepit.slack.models.{ SlackMessage, SlackUsername, SlackUserId, SlackTeamId }
import com.keepit.social.twitter.{ RawTweet, TwitterUserId }
import play.api.libs.json.{ JsValue, Reads, Format, OWrites, Json, Writes }
import com.keepit.common.core.jsObjectExtensionOps

object ImageUrls {
  val SLACK_LOGO = "https://djty7jcqog9qu.cloudfront.net/oa/98c4c6dc6bf8aeca952d2316df5b242b_200x200-0x0-200x200_cs.png"
  val KIFI_LOGO = "https://d1dwdv9wd966qu.cloudfront.net/img/favicon64x64.7cc6dd4.png"
  val TWITTER_LOGO = "https://d1dwdv9wd966qu.cloudfront.net/img/twitter_logo_104.png"
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
  val Fake = KifiUser(
    id = "42424242-4242-4242-424242424242",
    name = "fake",
    picture = ImageUrls.KIFI_LOGO,
    url = Path.base
  )
  case class KifiUser(id: String, name: String, picture: String, url: String) extends BasicAuthor(AuthorKind.Kifi)
  case class SlackUser(id: String, name: String, picture: String, url: String) extends BasicAuthor(AuthorKind.Slack)
  case class TwitterUser(id: String, name: String, picture: String, url: String) extends BasicAuthor(AuthorKind.Twitter)

  def apply(attr: SourceAttribution, basicUserOpt: Option[BasicUser])(implicit imageConfig: S3ImageConfig): BasicAuthor = {
    basicUserOpt.map(BasicAuthor.fromUser).getOrElse(BasicAuthor.fromSource(attr))
  }

  def fromUser(user: BasicUser)(implicit imageConfig: S3ImageConfig): BasicAuthor = KifiUser(
    id = user.externalId.id,
    name = user.fullName,
    picture = user.picturePath.getUrl,
    url = user.path.absolute
  )
  def fromSource(source: SourceAttribution): BasicAuthor = source match {
    case SlackAttribution(msg, teamId) => SlackUser(
      id = msg.userId.value,
      name = s"@${msg.username.value}",
      picture = ImageUrls.SLACK_LOGO,
      url = msg.permalink
    )
    case TwitterAttribution(tweet) => TwitterUser(
      id = tweet.user.id.id.toString,
      name = s"@${tweet.user.name}",
      picture = ImageUrls.TWITTER_LOGO,
      url = tweet.permalink
    )
  }

  private val kifiFormat = Json.format[KifiUser]
  private val slackFormat = Json.format[SlackUser]
  private val twitterFormat = Json.format[TwitterUser]
  implicit val format: Format[BasicAuthor] = Format(
    Reads[BasicAuthor] { js: JsValue =>
      (js \ "kind").as[String] match {
        case AuthorKind.Kifi.value => kifiFormat.reads(js)
        case AuthorKind.Slack.value => slackFormat.reads(js)
        case AuthorKind.Twitter.value => twitterFormat.reads(js)
      }
    },
    Writes[BasicAuthor] { author =>
      (author match {
        case ba: KifiUser => kifiFormat.writes(ba)
        case ba: SlackUser => slackFormat.writes(ba)
        case ba: TwitterUser => twitterFormat.writes(ba)
      }) ++ Json.obj("kind" -> author.kind.value)
    }
  )
}
