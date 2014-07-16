package com.keepit.search.feed

import com.keepit.common.db.Id
import com.keepit.model.{ Username, NormalizedURI, User, UrlHash }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.common.db.ExternalId
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.keepit.search.sharding.Shard

case class FixedResultFeedModule() extends ScalaModule {
  override def configure() {
    bind[FeedCommander].to[FixedResultFeedCommander].in[AppScoped]
  }
}

class FixedResultFeedCommander extends FeedCommander {
  private val t0 = new DateTime(2014, 1, 30, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
  private val t1 = new DateTime(2014, 1, 30, 22, 11, 0, 0, DEFAULT_DATE_TIME_ZONE)
  private val basicUsers = Seq(
    BasicUser(
      externalId = ExternalId[User]("4e5f7b8c-951b-4497-8661-012345678901"),
      firstName = "u1",
      lastName = "fake",
      pictureName = "u1.png",
      username = Some(Username("u1"))
    ),

    BasicUser(
      externalId = ExternalId[User]("4e5f7b8c-951b-4497-8661-012345678902"),
      firstName = "u2",
      lastName = "fake",
      pictureName = "u2.png",
      username = Some(Username("u2"))
    )
  )

  private val feeds = Seq(
    Feed(
      uri = NormalizedURI(id = Some(Id[NormalizedURI](1L)), externalId = ExternalId[NormalizedURI]("abc12345-1234-1234-1234-012345678901"), createdAt = t0, updatedAt = t0, title = Some("kifi"), urlHash = UrlHash("h1"), url = "http://kifi.com"),
      sharingUsers = Seq(basicUsers(0)),
      firstKeptAt = t0,
      totalKeepersSize = 10
    ),

    Feed(
      uri = NormalizedURI(id = Some(Id[NormalizedURI](2L)), createdAt = t1, updatedAt = t1, externalId = ExternalId[NormalizedURI]("abc12345-1234-1234-1234-012345678902"), title = Some("42go"), urlHash = UrlHash("h2"), url = "http://42go.com"),
      sharingUsers = basicUsers,
      firstKeptAt = t1,
      totalKeepersSize = 20
    )
  )

  def getFeeds(userId: Id[User], limit: Int): Seq[Feed] = feeds

  def distFeeds(shards: Set[Shard[NormalizedURI]], userId: Id[User], limit: Int): Seq[Feed] = ???
}
