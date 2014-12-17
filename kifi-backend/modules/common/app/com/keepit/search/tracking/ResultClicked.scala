package com.keepit.search.tracking

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.search.{ SearchConfigExperiment, ArticleSearchResult }
import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.time.DateTimeJsonFormat
import play.api.libs.functional.syntax._

case class ResultClicked(
  userId: Id[User],
  searchExperiment: Option[Id[SearchConfigExperiment]],
  queryUUID: Option[ExternalId[ArticleSearchResult]],
  query: String,
  kifiResults: Int,
  resultSource: String,
  resultPosition: Int,
  keptUri: Option[Id[NormalizedURI]],
  isUserKeep: Boolean,
  time: DateTime)

object ResultClicked {
  implicit val format = (
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'searchExperiment).formatNullable(Id.format[SearchConfigExperiment]) and
    (__ \ 'queryUUID).formatNullable(ExternalId.format[ArticleSearchResult]) and
    (__ \ 'query).format[String] and
    (__ \ 'kifiResults).format[Int] and
    (__ \ 'resultSource).format[String] and
    (__ \ 'resultPosition).format[Int] and
    (__ \ 'keptUri).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'isUserKeep).format[Boolean] and
    (__ \ 'time).format(DateTimeJsonFormat)
  )(ResultClicked.apply, unlift(ResultClicked.unapply))
}

case class SearchEnded(
  userId: Id[User],
  searchExperiment: Option[Id[SearchConfigExperiment]],
  queryUUID: Option[ExternalId[ArticleSearchResult]],
  kifiResults: Int,
  kifiResultsClicked: Int,
  googleResultsClicked: Int,
  time: DateTime)

object SearchEnded {
  implicit val format = (
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'searchExperiment).formatNullable(Id.format[SearchConfigExperiment]) and
    (__ \ 'queryUUID).formatNullable(ExternalId.format[ArticleSearchResult]) and
    (__ \ 'kifiResults).format[Int] and
    (__ \ 'kifiResultsClicked).format[Int] and
    (__ \ 'googleResultsClicked).format[Int] and
    (__ \ 'time).format(DateTimeJsonFormat)
  )(SearchEnded.apply, unlift(SearchEnded.unapply))
}