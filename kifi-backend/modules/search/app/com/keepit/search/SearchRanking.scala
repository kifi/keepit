package com.keepit.search

import play.api.libs.json._

case class SearchRanking(orderBy: String) extends AnyVal
object SearchRanking {
  val relevancy = SearchRanking("relevancy") // default
  val recency = SearchRanking("recency")
  val all = Set(relevancy, recency)

  def parse(orderBy: String): Option[SearchRanking] = Some(SearchRanking(orderBy.trim.toLowerCase)).filter(all.contains)

  implicit val format = new Format[SearchRanking] {
    def reads(json: JsValue) = for {
      orderBy <- json.validate[String]
      validRanking <- parse(orderBy).map(JsSuccess(_)) getOrElse JsError(s"Unknown search ranking: $orderBy")
    } yield validRanking

    def writes(orderBy: SearchRanking) = JsString(orderBy.orderBy)
  }

  def default = relevancy
}
