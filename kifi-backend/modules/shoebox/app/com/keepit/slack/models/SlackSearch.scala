package com.keepit.slack.models

import org.joda.time.LocalDate
import play.api.libs.json.{ Json, Reads }
import play.api.libs.functional.syntax._
import com.kifi.macros.json

case class SlackSearchRequest(query: SlackSearchRequest.Query, optional: SlackSearchRequest.Param*)

object SlackSearchRequest {
  sealed abstract class Param(val name: String, val value: Option[String])

  case class Query(query: String) extends Param("query", Some(query))
  object Query {
    def apply(queries: Query*): Query = Query(queries.map(_.query).mkString(" "))

    implicit def fromString(query: String): Query = Query(query)
    def in(channelName: SlackChannelName) = Query(s"in:${channelName.value}")
    def from(username: SlackUsername) = Query(s"from:${username.value}")
    def before(date: LocalDate) = Query(s"before:$date")
    def after(date: LocalDate) = Query(s"after:$date")
    val hasLink = Query(s"has:link")

    implicit val reads = Reads.of[String].map(Query(_))
  }

  sealed abstract class Sort(sort: String) extends Param("sort", Some(sort))
  object Sort {
    case object ByScore extends Sort("score")
    case object ByTimestamp extends Sort("timestamp")
  }

  sealed abstract class SortDirection(dir: String) extends Param("sort_dir", Some(dir))
  object SortDirection {
    case object Descending extends SortDirection("desc")
    case object Ascending extends SortDirection("asc")
  }

  object Highlight extends Param("highlight", Some("1"))

  case class Page(page: Int) extends Param("page", Some(page.toString))
  case class PageSize(count: Int) extends Param("count", Some(count.toString))
}

case class SlackSearchResponse(query: SlackSearchRequest.Query, messages: SlackSearchResponse.Messages)

object SlackSearchResponse {

  @json case class Paging(count: Int, total: Int, page: Int, pages: Int)
  @json case class Messages(total: Int, paging: Paging, matches: Seq[SlackMessage])

  implicit val reads = Json.reads[SlackSearchResponse]
}
