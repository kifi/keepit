package com.keepit.search

import com.keepit.common.db.{ExternalId, Id}
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.json._
import com.keepit.common.logging.Logging
import com.keepit.serializer.TraversableFormat

//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchHit(
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
  uuid: ExternalId[ArticleSearchResult],
  query: String,
  hits: Seq[PersonalSearchResult],
  mayHaveMoreHits: Boolean,
  show: Boolean,
  experimentId: Option[Id[SearchConfigExperiment]],
  context: String,
  collections: Seq[ExternalId[Collection]] = Nil,
  expertNames: Seq[String] = Nil)

object PersonalSearchResultPacket extends Logging {
  implicit val writes = new Writes[PersonalSearchResultPacket] {
    def writes(res: PersonalSearchResultPacket): JsValue =
      try {
        JsObject(List(
          "uuid" -> JsString(res.uuid.toString),
          "query" -> JsString(res.query),
          "hits" -> JsArray(res.hits.map(PersonalSearchResult.format.writes)),
          "mayHaveMore" -> JsBoolean(res.mayHaveMoreHits),
          "show" -> JsBoolean(res.show),
          "experimentId" -> res.experimentId.map(id => JsNumber(id.id)).getOrElse(JsNull),
          "context" -> JsString(res.context),
          "expertNames" -> JsString(res.expertNames.mkString("\t"))
        ))
      } catch {
        case e: Throwable =>
          log.error("can't serialize %s".format(res))
          throw e
      }
  }
}

object PersonalSearchResult extends Logging {
  implicit val format = new Format[PersonalSearchResult] {
    def writes(res: PersonalSearchResult): JsValue =
      try {
        JsObject(List(
          "count" -> JsNumber(res.count),
          "bookmark" -> PersonalSearchHit.writes(res.hit),
          "users" -> Json.toJson(res.users),
          "score" -> JsNumber(res.score),
          "isMyBookmark" -> JsBoolean(res.isMyBookmark),
          "isPrivate" -> JsBoolean(res.isPrivate)
        ))
      } catch {
        case e: Throwable =>
          log.error("can't serialize %s".format(res))
          throw e
      }

    def reads(json: JsValue): JsResult[PersonalSearchResult] =
      try {
        val isPrivate = (json \ "isPrivate").as[Boolean]
        JsSuccess(PersonalSearchResult(
          hit = PersonalSearchHit.reads(json \ "bookmark", isPrivate),
          count = (json \ "count").as[Int],
          isMyBookmark = (json \ "isMyBookmark").as[Boolean],
          isPrivate = isPrivate,
          users = TraversableFormat.seq[BasicUser].reads(json \ "users").get,
          score = (json \ "score").as[Float],
          isNew = (json \ "isNew").as[Boolean]
        ))
      } catch {
        case e: Throwable =>
          log.error("can't deserialize %s".format(json))
          throw e
      }
  }
}

object PersonalSearchHit extends Writes[PersonalSearchHit] with Logging {
    def writes(hit: PersonalSearchHit): JsValue = {

      var json = Json.obj(
        "title" -> hit.title,
        "url" -> hit.url
      )

      json = addMatches(json, hit)
      json = addCollections(json, hit)
      json = json ++ hit.bookmarkId.map(id => Json.obj("id" -> id.id)).getOrElse(Json.obj())
      json
    }

    private def addMatches(json: JsObject, hit: PersonalSearchHit): JsObject = {
      var matches = Json.obj()
      matches = addTitleMatches(matches, hit)
      matches = addUrlMatches(matches, hit)

      if (matches.keys.size == 0) json
      else json + ("matches" -> matches)
    }

    private def addTitleMatches(json: JsObject, hit: PersonalSearchHit): JsObject = {
      if (hit.titleMatches.nonEmpty) {
        json + ("title" -> JsArray(hit.titleMatches.map(h => Json.arr(h._1, (h._2 - h._1)))))
      } else json
    }
    private def addUrlMatches(json: JsObject, hit: PersonalSearchHit): JsObject = {
      if (hit.urlMatches.nonEmpty) {
        json + ("url" -> JsArray(hit.urlMatches.map(h => Json.arr(h._1, (h._2 - h._1)))))
      } else json
    }

    private def addCollections(json: JsObject, hit: PersonalSearchHit): JsObject = {
      hit.collections match {
        case Some(collections) =>
          json + ("collections" -> Json.toJson(collections.map(_.id)))
        case None => json
      }
    }

  def reads(json: JsValue, isPrivate: Boolean): PersonalSearchHit = PersonalSearchHit(
    title = (json \ "title").asOpt[String].filter(_.nonEmpty),
    url = (json \ "url").as[String],
    isPrivate = isPrivate,
    titleMatches = readMatches(json \ "matches" \ "title"),
    urlMatches = readMatches(json \ "matches" \ "url"),
    bookmarkId = ExternalId.asOpt[Bookmark]((json \ "id").as[String]),
    collections = (json \ "collections").asOpt[JsArray].map { case JsArray(ids) => ids.map(id => ExternalId[Collection](id.as[String])) }
  )

  private def readMatches(matches: JsValue): Seq[(Int, Int)] = matches.asOpt[JsArray] map {
    case JsArray(pairs) => pairs.map { case JsArray(Seq(JsNumber(start), JsNumber(len))) => (start.toInt, (start + len).toInt) }
  } getOrElse(Seq.empty)
}
