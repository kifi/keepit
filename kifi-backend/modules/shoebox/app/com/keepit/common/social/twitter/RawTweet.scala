package com.keepit.common.social.twitter

import java.util.Locale

import com.keepit.model.TwitterId
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class RawTweet(
  id: TwitterId,
  createdAt: DateTime,
  text: String,
  source: String,
  user: RawTweet.User,
  entities: RawTweet.Entities,
  retweetedStatus: Option[RawTweet.RawRetweet],
  favoriteCount: Option[Int],
  retweetCount: Option[Int],
  inReplyToStatusId: Option[TwitterId],
  inReplyToUserId: Option[TwitterId],
  inReplyToScreenName: Option[String],
  lang: Option[String], // BCP 47
  possiblySensitive: Option[Boolean],
  originalJson: JsValue)
object RawTweet {
  implicit def format: Reads[RawTweet] = (
    (__ \ 'id).read[TwitterId] and
    (__ \ 'created_at).read[DateTime](twitterDateReads(java.util.Locale.ENGLISH)) and
    (__ \ 'text).read[String] and
    (__ \ 'source).read[String] and
    (__ \ 'user).read[RawTweet.User] and
    (__ \ 'entities).read[RawTweet.Entities] and
    (__ \ 'retweeted_status).readNullable[RawRetweet] and
    (__ \ 'favorite_count).readNullable[Int] and
    (__ \ 'retweet_count).readNullable[Int] and
    (__ \ 'in_reply_to_status_id).readNullable[TwitterId] and
    (__ \ 'in_reply_to_user_id).readNullable[TwitterId] and
    (__ \ 'in_reply_to_screen_name).readNullable[String] and
    (__ \ 'lang).readNullable[String] and
    (__ \ 'possibly_sensitive).readNullable[Boolean] and
    new Reads[JsValue] {
      def reads(json: JsValue): JsResult[JsValue] = JsSuccess(json)
    }
  )(RawTweet.apply _)

  // This is because Play json has a bug with recursive types
  case class RawRetweet(
    id: TwitterId,
    createdAt: DateTime,
    text: String,
    source: String,
    user: User,
    entities: Entities,
    favoriteCount: Option[Int],
    retweetCount: Option[Int],
    inReplyToStatusId: Option[TwitterId],
    inReplyToUserId: Option[TwitterId],
    inReplyToScreenName: Option[String],
    lang: Option[String], // BCP 47
    possiblySensitive: Option[Boolean])

  object RawRetweet {
    implicit def format: Reads[RawRetweet] = (
      (__ \ 'id_str).read[TwitterId] and
      (__ \ 'created_at).read[DateTime](twitterDateReads(java.util.Locale.ENGLISH)) and
      (__ \ 'text).read[String] and
      (__ \ 'source).read[String] and
      (__ \ 'user).read[RawTweet.User] and
      (__ \ 'entities).read[RawTweet.Entities] and
      (__ \ 'favorite_count).readNullable[Int] and
      (__ \ 'retweet_count).readNullable[Int] and
      (__ \ 'in_reply_to_status_id).readNullable[TwitterId] and
      (__ \ 'in_reply_to_user_id).readNullable[TwitterId] and
      (__ \ 'in_reply_to_screen_name).readNullable[String] and
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
    implicit def format: Reads[TweetIndices] = __.read[JsArray].map(r => TweetIndices(r(0).as[Int], r(1).as[Int]))
  }

  // https://dev.twitter.com/overview/api/entities-in-twitter-objects
  import Entity._
  case class Entities(
    userMentions: Seq[UserMentionsEntity],
    media: Seq[MediaEntity],
    hashtags: Seq[HashtagEntity],
    urls: Seq[UrlEntity])
  object Entities {
    implicit def format: Reads[Entities] = (
      (__ \ 'user_mentions).read[Seq[UserMentionsEntity]] and
      ((__ \ 'media).read[Seq[MediaEntity]].orElse(Reads.pure(Seq.empty))) and
      (__ \ 'hashtags).read[Seq[HashtagEntity]] and
      (__ \ 'urls).read[Seq[UrlEntity]]
    )(Entities.apply _)
  }

  sealed trait Entity
  object Entity {
    case class UserMentionsEntity(name: String, screenName: String, indices: TweetIndices, id: TwitterId) extends Entity
    object UserMentionsEntity {
      implicit def format: Reads[UserMentionsEntity] = (
        (__ \ 'name).read[String] and
        (__ \ 'screen_name).read[String] and
        (__ \ 'indices).read[TweetIndices] and
        (__ \ 'id).read[TwitterId]
      )(UserMentionsEntity.apply _)
    }

    // type is only "photo" for now, per Twitter docs. May change in the future.
    case class MediaEntity(id: TwitterId, indices: TweetIndices, mediaUrlHttps: String, url: String, displayUrl: String, expandedUrl: String, sizes: Map[String, MediaEntity.Size])
    object MediaEntity {
      // Sizes key can be "medium", "thumb", "small", or "large"
      case class Size(w: Int, h: Int, resize: String)
      object Size {
        implicit def format: Reads[Size] = Json.reads[Size]
      }

      implicit def format: Reads[MediaEntity] = (
        (__ \ 'id).read[TwitterId] and
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
      implicit def format: Reads[HashtagEntity] = (
        (__ \ 'text).read[String] and
        (__ \ 'indices).read[TweetIndices]
      )(HashtagEntity.apply _)
    }

    case class UrlEntity(indices: TweetIndices, url: String, expandedUrl: String, displayUrl: String)
    object UrlEntity {
      implicit def format: Reads[UrlEntity] = (
        (__ \ 'indices).read[TweetIndices] and
        (__ \ 'url).read[String] and
        (__ \ 'expanded_url).read[String] and
        (__ \ 'display_url).read[String]
      )(UrlEntity.apply _)
    }
  }

  case class User(
    name: String,
    screenName: String,
    id: TwitterId,
    profileImageUrlHttps: String)
  object User {
    implicit def format: Reads[User] = (
      (__ \ 'name).read[String] and
      (__ \ 'screen_name).read[String] and
      (__ \ 'id).read[TwitterId] and
      (__ \ 'profile_image_url_https).read[String]
    )(User.apply _)
  }
}
