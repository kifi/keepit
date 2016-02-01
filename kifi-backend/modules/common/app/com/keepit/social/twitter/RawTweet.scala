package com.keepit.social.twitter

import java.util.Locale

import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.kifi.macros.json

import scala.util.Try

sealed trait TwitterId[I <: TwitterId[I]] {
  val id: Long
}

sealed trait TwitterIdCompanion[I <: TwitterId[I]] {
  // https://groups.google.com/forum/#!topic/twitter-development-talk/ahbvo3VTIYI
  def apply(id: Long): I
  implicit val longFormat: Format[I] = Format(Reads(id => id.validate[Long].map(apply)), Writes(id => JsNumber(id.id)))
  val stringReads: Reads[I] = Reads(value => value.validate[String].flatMap(idStr => Try(idStr.toLong).map(id => JsSuccess(apply(id))) getOrElse JsError(s"Invalid $this: $idStr")))
} 

case class TwitterUserId(id: Long) extends TwitterId[TwitterUserId]
object TwitterUserId extends TwitterIdCompanion[TwitterUserId]

case class TwitterStatusId(id: Long) extends TwitterId[TwitterStatusId]
object TwitterStatusId extends TwitterIdCompanion[TwitterStatusId]

case class TwitterMediaId(id: Long) extends TwitterId[TwitterMediaId]
object TwitterMediaId extends TwitterIdCompanion[TwitterMediaId]

@json case class TwitterHandle(value: String) {
  override def toString = value
}

case class RawTweet(
  id: TwitterStatusId,
  createdAt: DateTime,
  text: String,
  source: String,
  user: RawTweet.User,
  entities: RawTweet.Entities,
  retweetedStatus: Option[RawTweet.RawRetweet],
  favoriteCount: Option[Int],
  retweetCount: Option[Int],
  inReplyToStatusId: Option[TwitterStatusId],
  inReplyToUserId: Option[TwitterUserId],
  inReplyToScreenName: Option[TwitterHandle],
  lang: Option[String], // BCP 47
  possiblySensitive: Option[Boolean],
  originalJson: JsValue) {
  def getUrl: String = RawTweet.getUrl(user.screenName, id)
}

object RawTweet {

  def getUrl(handle: TwitterHandle, statusId: TwitterStatusId): String = s"https://twitter.com/${handle.value}/status/${statusId.id}"

  private implicit val reads: Reads[RawTweet] = (
    (__ \ 'id_str).read(TwitterStatusId.stringReads) and
    (__ \ 'created_at).read[DateTime](twitterDateReads(java.util.Locale.ENGLISH)) and
    (__ \ 'text).read[String] and
    (__ \ 'source).read[String] and
    (__ \ 'user).read[RawTweet.User] and
    (__ \ 'entities).read[RawTweet.Entities] and
    (__ \ 'retweeted_status).readNullable[RawRetweet] and
    (__ \ 'favorite_count).readNullable[Int] and
    (__ \ 'retweet_count).readNullable[Int] and
    (__ \ 'in_reply_to_status_id_str).readNullable(TwitterStatusId.stringReads) and
    (__ \ 'in_reply_to_user_id_str).readNullable(TwitterUserId.stringReads) and
    (__ \ 'in_reply_to_screen_name).readNullable[TwitterHandle] and
    (__ \ 'lang).readNullable[String] and
    (__ \ 'possibly_sensitive).readNullable[Boolean] and
    Reads(JsSuccess(_))
  )(RawTweet.apply _)

  private implicit val writes: Writes[RawTweet] = Writes(r => r.originalJson)
  implicit val format = Format(reads, writes)

  // This is because Play json has a bug with recursive types
  case class RawRetweet(
    id: TwitterStatusId,
    createdAt: DateTime,
    text: String,
    source: String,
    user: User,
    entities: Entities,
    favoriteCount: Option[Int],
    retweetCount: Option[Int],
    inReplyToStatusId: Option[TwitterStatusId],
    inReplyToUserId: Option[TwitterUserId],
    inReplyToScreenName: Option[TwitterHandle],
    lang: Option[String], // BCP 47
    possiblySensitive: Option[Boolean]) {
    def getUrl: String = RawTweet.getUrl(user.screenName, id)
  }

  object RawRetweet {
    implicit val reads: Reads[RawRetweet] = (
      (__ \ 'id_str).read(TwitterStatusId.stringReads) and
      (__ \ 'created_at).read[DateTime](twitterDateReads(java.util.Locale.ENGLISH)) and
      (__ \ 'text).read[String] and
      (__ \ 'source).read[String] and
      (__ \ 'user).read[RawTweet.User] and
      (__ \ 'entities).read[RawTweet.Entities] and
      (__ \ 'favorite_count).readNullable[Int] and
      (__ \ 'retweet_count).readNullable[Int] and
      (__ \ 'in_reply_to_status_id_str).readNullable(TwitterStatusId.stringReads) and
      (__ \ 'in_reply_to_user_id_str).readNullable(TwitterUserId.stringReads) and
      (__ \ 'in_reply_to_screen_name).readNullable[TwitterHandle] and
      (__ \ 'lang).readNullable[String] and
      (__ \ 'possibly_sensitive).readNullable[Boolean]
    )(RawRetweet.apply _)
  }

  import play.api.data.validation.ValidationError
  private def twitterDateReads(locale: Locale, corrector: String => String = identity): Reads[DateTime] = new Reads[DateTime] {
    val pattern = "yyyy-MM-dd HH:mm:ss Z"
    val df = org.joda.time.format.DateTimeFormat.forPattern(pattern).withLocale(locale)

    val pattern_fu = "EEE MMM dd HH:mm:ss Z yyyy"
    val df_fu = org.joda.time.format.DateTimeFormat.forPattern(pattern_fu).withLocale(locale)

    def reads(json: JsValue): JsResult[DateTime] = json match {
      case JsNumber(d) => JsSuccess(new DateTime(d.toLong))
      case JsString(s) => parseDate(corrector(s)) match {
        case Some(d) => JsSuccess(d)
        case None => JsError(Seq(JsPath() -> Seq(ValidationError("validate.error.expected.jodadate.format", pattern))))
      }
      case _ => JsError(Seq(JsPath() -> Seq(ValidationError("validate.error.expected.date"))))
    }

    private def parseDate(input: String): Option[DateTime] =
      scala.util.control.Exception.allCatch[DateTime].opt(DateTime.parse(input, df)).orElse {
        scala.util.control.Exception.allCatch[DateTime].opt(DateTime.parse(input, df_fu))
      }
  }

  case class TweetIndices(start: Int, end: Int)
  object TweetIndices {
    implicit val reads: Reads[TweetIndices] = __.read[JsArray].map(r => TweetIndices(r(0).as[Int], r(1).as[Int]))
  }

  // https://dev.twitter.com/overview/api/entities-in-twitter-objects
  import com.keepit.social.twitter.RawTweet.Entity._
  case class Entities(
    userMentions: Seq[UserMentionsEntity],
    media: Seq[MediaEntity],
    hashtags: Seq[HashtagEntity],
    urls: Seq[UrlEntity])
  object Entities {
    implicit val reads: Reads[Entities] = (
      (__ \ 'user_mentions).read[Seq[UserMentionsEntity]] and
      ((__ \ 'media).read[Seq[MediaEntity]].orElse(Reads.pure(Seq.empty))) and
      (__ \ 'hashtags).read[Seq[HashtagEntity]] and
      (__ \ 'urls).read[Seq[UrlEntity]]
    )(Entities.apply _)
  }

  sealed trait Entity
  object Entity {
    case class UserMentionsEntity(name: String, screenName: TwitterHandle, indices: TweetIndices, id: TwitterUserId) extends Entity
    object UserMentionsEntity {
      implicit val reads: Reads[UserMentionsEntity] = (
        (__ \ 'name).read[String] and
        (__ \ 'screen_name).read[TwitterHandle] and
        (__ \ 'indices).read[TweetIndices] and
        (__ \ 'id_str).read(TwitterUserId.stringReads)
      )(UserMentionsEntity.apply _)
    }

    // type is only "photo" for now, per Twitter docs. May change in the future.
    case class MediaEntity(id: TwitterMediaId, indices: TweetIndices, mediaUrlHttps: String, url: String, displayUrl: String, expandedUrl: String, sizes: Map[String, MediaEntity.Size])
    object MediaEntity {
      // Sizes key can be "medium", "thumb", "small", or "large"
      case class Size(w: Int, h: Int, resize: String)
      object Size {
        implicit val reads: Reads[Size] = Json.reads[Size]
      }

      implicit val reads: Reads[MediaEntity] = (
        (__ \ 'id_str).read(TwitterMediaId.stringReads) and
        (__ \ 'indices).read[TweetIndices] and
        (__ \ 'media_url_https).read[String] and
        (__ \ 'url).read[String] and
        (__ \ 'display_url).read[String] and
        (__ \ 'expanded_url).read[String] and
        (__ \ 'sizes).read[Map[String, Size]].orElse(new Reads[Map[String, Size]] {
          def reads(j: JsValue): JsResult[Map[String, Size]] = {
            val map = j \ "sizes" match {
              case JsArray(sizes) =>
                val sorted = sizes.map(_.as[Size]).distinct.sortBy(_.h)
                Seq("thumb", "small", "medium", "large").zipWithIndex.map {
                  case (s, x) =>
                    sorted.lift(x).map(s -> _)
                }.flatten.toMap
              case other =>
                println(s"Couldn't parse media entity sizes: $other")
                Map.empty[String, Size]
            }
            if (map.isEmpty) JsError(Seq(JsPath() -> Seq(ValidationError("validate.error.expected.size"))))
            else JsSuccess(map)
          }
        })
      )(MediaEntity.apply _)
    }

    case class HashtagEntity(text: String, indices: TweetIndices)
    object HashtagEntity {
      implicit val reads: Reads[HashtagEntity] = (
        (__ \ 'text).read[String] and
        (__ \ 'indices).read[TweetIndices]
      )(HashtagEntity.apply _)
    }

    case class UrlEntity(indices: TweetIndices, url: String, expandedUrl: String, displayUrl: String)
    object UrlEntity {
      implicit val reads: Reads[UrlEntity] = (
        (__ \ 'indices).read[TweetIndices] and
        (__ \ 'url).read[String] and
        (__ \ 'expanded_url).read[String] and
        (__ \ 'display_url).read[String]
      )(UrlEntity.apply _)
    }
  }

  case class User(
    name: String,
    screenName: TwitterHandle,
    id: TwitterUserId,
    profileImageUrlHttps: String)
  object User {
    implicit val reads: Reads[User] = (
      (__ \ 'name).read[String] and
      (__ \ 'screen_name).read[TwitterHandle] and
      (__ \ 'id_str).read(TwitterUserId.stringReads) and
      (__ \ 'profile_image_url_https).read[String]
    )(User.apply _)
  }
}
