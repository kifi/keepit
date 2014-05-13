package com.keepit.search.feed

import org.joda.time.DateTime
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.social.BasicUser
import com.keepit.common.time._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import org.joda.time.DateTime
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.common.db.ExternalId
import com.keepit.model.User

case class FixedResultFeedModule() {

}

class FixedResultSearchCommander {
  private val t0 = new DateTime(2014, 1, 30, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
  private val t1 = new DateTime(2014, 1, 30, 22, 00, 0, 0, DEFAULT_DATE_TIME_ZONE)
  private val basicUsers = Seq(
    BasicUser(
      externalId = ExternalId[User]("4e5f7b8c-951b-4497-8661-012345678901"),
      firstName = "u1",
      lastName = "fake",
      pictureName = "u1.png"
    ),

    BasicUser(
      externalId = ExternalId[User]("4e5f7b8c-951b-4497-8661-012345678902"),
      firstName = "u2",
      lastName = "fake",
      pictureName = "u2.png"
    )
  )

  private val feeds = Seq(
    Feed(
      uri = NormalizedURI(id = Some(Id[NormalizedURI]), createdAt = t0, updatedAt = t0, url = "http://kifi.com"),
      sharingUsers

    )

  )

  private val result = {
    ""
  }
}