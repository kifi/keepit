package com.keepit.search.feed

import org.joda.time.DateTime
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.social.BasicUser
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Feed(uri: NormalizedURI, sharingUsers: Seq[BasicUser], firstKeptAt: DateTime, totalKeepersSize: Int)

object Feed {
  implicit val format = (
    (__ \'uri).format[NormalizedURI] and
    (__ \'sharingUsers).format[Seq[BasicUser]] and
    (__ \'firstKeptAt).format(DateTimeJsonFormat) and
    (__ \'totalKeepersSize).format[Int]
  )(Feed.apply, unlift(Feed.unapply))
}
