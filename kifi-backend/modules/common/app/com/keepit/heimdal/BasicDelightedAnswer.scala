package com.keepit.heimdal

import com.keepit.common.net.UserAgent
import com.kifi.macros.json
import play.api.libs.json._
import play.api.libs.functional.syntax._

@json case class DelightedAnswerSource(value: String)

object DelightedAnswerSources {
  val IOS = DelightedAnswerSource("ios")
  val ANDROID = DelightedAnswerSource("android")
  val WEBSITE = DelightedAnswerSource("website")
  val UNKNOWN = DelightedAnswerSource("unknown")

  def fromUserAgent(userAgentOpt: Option[UserAgent]) = {
    userAgentOpt map { ua =>
      if (ua.isKifiIphoneApp) DelightedAnswerSources.IOS
      else if (ua.isKifiAndroidApp) DelightedAnswerSources.ANDROID
      else DelightedAnswerSources.WEBSITE
    } getOrElse DelightedAnswerSources.UNKNOWN
  }
}

case class BasicDelightedAnswer(score: Int, comment: Option[String], source: DelightedAnswerSource)

object BasicDelightedAnswer {

  implicit def reads(implicit source: DelightedAnswerSource = DelightedAnswerSources.UNKNOWN): Reads[BasicDelightedAnswer] = (
    (__ \ "score").read[Int](Reads.min(0) or Reads.max(10)) and
    (__ \ "comment").readNullable[String] and
    (__ \ "source").readNullable[DelightedAnswerSource].map(_.getOrElse(source))
  )(BasicDelightedAnswer.apply _)

  implicit val writes = Json.writes[BasicDelightedAnswer]
}
