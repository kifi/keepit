package com.keepit.social

import com.keepit.model._
import play.api.libs.json.{ Json, Writes }

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
