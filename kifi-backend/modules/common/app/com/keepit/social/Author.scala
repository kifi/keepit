package com.keepit.social

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.json.EnumFormat
import com.keepit.common.path.Path
import com.keepit.common.reflection.Enumerator
import com.keepit.common.store.{ StaticImageUrls, S3ImageConfig }
import com.keepit.common.strings.ValidLong
import com.keepit.model._
import com.keepit.slack.models.{ SlackMessage, SlackUsername, SlackUserId, SlackTeamId }
import com.keepit.social.twitter.{ RawTweet, TwitterUserId }
import play.api.libs.json.{ JsValue, Reads, Format, OWrites, Json, Writes }
import com.keepit.common.core.jsObjectExtensionOps

sealed abstract class Author(val kind: AuthorKind)
object Author {
  final case class KifiUser(userId: Id[User]) extends Author(AuthorKind.Kifi)
  final case class SlackUser(teamId: SlackTeamId, userId: SlackUserId) extends Author(AuthorKind.Slack)
  final case class TwitterUser(userId: TwitterUserId) extends Author(AuthorKind.Twitter)
  def fromSource(attr: RawSourceAttribution): Author = attr match {
    case RawTwitterAttribution(tweet) => TwitterUser(tweet.user.id)
    case RawSlackAttribution(msg, teamId) => SlackUser(teamId, msg.userId)
    case RawKifiAttribution(userId, _, _, _) => KifiUser(userId)
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

  def kifiUserId(author: Author): Option[Id[User]] = author match {
    case KifiUser(userId) => Some(userId)
    case _ => None
  }
}

sealed abstract class AuthorKind(val value: String)
object AuthorKind extends Enumerator[AuthorKind] {
  case object Kifi extends AuthorKind("kifi")
  case object Slack extends AuthorKind("slack")
  case object Twitter extends AuthorKind("twitter")
  case object Email extends AuthorKind("email")

  val all = _all
  def fromStr(str: String) = all.find(_.value == str)

  implicit val format: Format[AuthorKind] = EnumFormat.format(fromStr, _.value)
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
    name = "Someone",
    picture = StaticImageUrls.KIFI_LOGO,
    url = Path.base
  )
  case class KifiUser(id: String, name: String, picture: String, url: String) extends BasicAuthor(AuthorKind.Kifi)
  case class SlackUser(id: String, name: String, picture: String, url: String) extends BasicAuthor(AuthorKind.Slack)
  case class TwitterUser(id: String, name: String, picture: String, url: String) extends BasicAuthor(AuthorKind.Twitter)
  case class EmailUser(id: String, name: String, picture: String) extends BasicAuthor(AuthorKind.Email)

  def apply(attr: SourceAttribution, basicUserOpt: Option[BasicUser])(implicit imageConfig: S3ImageConfig): BasicAuthor = {
    basicUserOpt.map(BasicAuthor.fromUser).getOrElse(BasicAuthor.fromSource(attr))
  }

  def fromUser(user: BasicUser)(implicit imageConfig: S3ImageConfig): BasicAuthor = KifiUser(
    id = user.externalId.id,
    name = user.fullName,
    picture = user.picturePath.getUrl,
    url = user.path.absolute
  )
  def fromSource(source: SourceAttribution)(implicit imageConfig: S3ImageConfig): BasicAuthor = source match {
    case SlackAttribution(msg, teamId) => SlackUser(
      id = msg.userId.value,
      name = s"@${msg.username.value}",
      picture = StaticImageUrls.SLACK_LOGO,
      url = msg.permalink
    )
    case TwitterAttribution(tweet) => TwitterUser(
      id = tweet.user.id.id.toString,
      name = tweet.user.name,
      picture = StaticImageUrls.TWITTER_LOGO,
      url = tweet.permalink
    )
    case KifiAttribution(keptBy, _, _, _, _, _) => fromUser(keptBy)
  }
  def fromNonUser(nonUser: BasicNonUser): BasicAuthor = nonUser.kind match {
    case NonUserKinds.email =>
      EmailUser(
        nonUser.id,
        nonUser.firstName.getOrElse(nonUser.id),
        BasicUser.defaultImageForEmail(nonUser.id)
      )
  }

  private val kifiFormat = Json.format[KifiUser]
  private val slackFormat = Json.format[SlackUser]
  private val twitterFormat = Json.format[TwitterUser]
  private val emailFormat = Json.format[EmailUser]
  implicit val format: Format[BasicAuthor] = Format(
    Reads[BasicAuthor] { js: JsValue =>
      (js \ "kind").as[String] match {
        case AuthorKind.Kifi.value => kifiFormat.reads(js)
        case AuthorKind.Slack.value => slackFormat.reads(js)
        case AuthorKind.Twitter.value => twitterFormat.reads(js)
        case AuthorKind.Email.value => emailFormat.reads(js)
      }
    },
    Writes[BasicAuthor] { author =>
      (author match {
        case ba: KifiUser => kifiFormat.writes(ba)
        case ba: SlackUser => slackFormat.writes(ba)
        case ba: TwitterUser => twitterFormat.writes(ba)
        case ba: EmailUser => emailFormat.writes(ba)
      }) ++ Json.obj("kind" -> author.kind.value)
    }
  )
}
