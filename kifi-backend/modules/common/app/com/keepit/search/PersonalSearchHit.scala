package com.keepit.search

import com.keepit.common.db.{ExternalId, Id}
import com.keepit.model._
import com.keepit.social.BasicUser

//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchHit(
    id: Id[NormalizedURI],
    title: Option[String],
    url: String,
    isPrivate: Boolean,
    titleMatches: Seq[(Int, Int)],
    urlMatches: Seq[(Int, Int)],
    bookmarkId: Option[ExternalId[Bookmark]],
    collections: Option[Seq[ExternalId[Collection]]]
)

case class PersonalSearchResult(hit: PersonalSearchHit, count: Int, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[BasicUser], score: Float, isNew: Boolean)
case class PersonalSearchResultPacket(
  uuid: ExternalId[ArticleSearchResultRef],
  query: String,
  hits: Seq[PersonalSearchResult],
  mayHaveMoreHits: Boolean,
  show: Boolean,
  experimentId: Option[Id[SearchConfigExperiment]],
  context: String,
  expertNames: Seq[String] = Nil)
