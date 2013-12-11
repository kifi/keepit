package com.keepit.search

import com.keepit.common.db.{ExternalId, Id}
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.json._
import com.keepit.common.logging.Logging
import com.keepit.serializer.TraversableFormat

object KifiSearchResult extends Logging {
  def json(
    uuid: ExternalId[ArticleSearchResult],
    query: String,
    hits: Seq[KifiSearchHit],
    mayHaveMoreHits: Boolean,
    show: Boolean,
    experimentId: Option[Id[SearchConfigExperiment]],
    context: String,
    collections: Seq[ExternalId[Collection]] = Nil,
    expertNames: Seq[String] = Nil
  ): JsValue = {
    try {
      JsObject(List(
        "uuid" -> JsString(uuid.toString),
        "query" -> JsString(query),
        "hits" -> JsArray(hits.map(_.json)),
        "mayHaveMore" -> JsBoolean(mayHaveMoreHits),
        "show" -> JsBoolean(show),
        "experimentId" -> experimentId.map(id => JsNumber(id.id)).getOrElse(JsNull),
        "context" -> JsString(context),
        "expertNames" -> JsString(expertNames.mkString("\t"))
      ))
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize KifiSearchResult [uuid=$uuid][query=$query][hits=$hits][mayHaveMore=$mayHaveMoreHits][show=$show][experimentId=$experimentId][context=$context][expertNames=$expertNames]", e)
        throw e
    }
  }
}

class KifiSearchHit(val json: JsValue) extends AnyVal {
  //note: users.size != count if some users has the bookmark marked as private
  def title: Option[String] = new PersonalSearchHit(json \ "bookmark").title
  def url: String = new PersonalSearchHit(json \ "bookmark").url
  def isMyBookmark: Boolean = (json \ "isMyBookmark").as[Boolean]
  def isPrivate: Boolean = (json \ "isPrivate").as[Boolean]
  def count: Int = (json \ "count").as[Int]
  def users: Seq[BasicUser] = TraversableFormat.seq[BasicUser].reads(json \ "users").get
  def score: Float = (json \ "score").as[Float]
  def bookmark: PersonalSearchHit = new PersonalSearchHit(json \ "bookmark")
}

object KifiSearchHit extends Logging {
  def apply(json: JsValue) = new KifiSearchHit(json)
  def apply(
    hit: PersonalSearchHit,
    count: Int,
    isMyBookmark: Boolean,
    isPrivate: Boolean,
    users: Seq[BasicUser],
    score: Float
  ): KifiSearchHit = {
    try {
      new KifiSearchHit(JsObject(List(
          "count" -> JsNumber(count),
          "bookmark" -> hit.json,
          "users" -> Json.toJson(users),
          "score" -> JsNumber(score),
          "isMyBookmark" -> JsBoolean(isMyBookmark),
          "isPrivate" -> JsBoolean(isPrivate)
      )))
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize KifiSearchHit [hit=$hit][count=$count][users=$users][score=$score][isMybookmark=$isMyBookmark][isPrivate=$isPrivate]", e)
        throw e
    }
  }
}

class ShardSearchResult(val json: JsValue) extends AnyVal {
//  def friendStats: FriendStats =
}

class ShardSearchHit(val json: JsValue) extends AnyVal

object ShardSearchHit extends Logging {
  def apply(json: JsValue) = new ShardSearchHit(json)
  def apply(
    hit: PersonalSearchHit,
    count: Int,
    isMyBookmark: Boolean,
    isPrivate: Boolean,
    users: Seq[Id[User]],
    scoring: Scoring,
    friendStats: FriendStats
  ): ShardSearchHit = {
    try {
      new ShardSearchHit(JsObject(List(
        "count" -> JsNumber(count),
        "bookmark" -> hit.json,
        "users" -> JsArray(users.map(id => JsNumber(id.id))),
        "scoring" -> Json.toJson(scoring),
        "isMyBookmark" -> JsBoolean(isMyBookmark),
        "isPrivate" -> JsBoolean(isPrivate)
      )))
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize ShardSearchHit [hit=$hit][count=$count][users=$users][scoring=$scoring][isMybookmark=$isMyBookmark][isPrivate=$isPrivate]", e)
        throw e
    }
  }
}

class PersonalSearchHit(val json: JsValue) extends AnyVal {
  def title: Option[String] = (json \ "title").asOpt[String].filter(_.nonEmpty)
  def url: String = (json \ "url").as[String]
  def titleMatches: Seq[(Int, Int)] = readMatches(json \ "matches" \ "title")
  def urlMatches: Seq[(Int, Int)] = readMatches(json \ "matches" \ "url")
  def collections: Option[Seq[ExternalId[Collection]]] = (json \ "collections").asOpt[JsArray].map{ case JsArray(ids) => ids.map(id => ExternalId[Collection](id.as[String])) }
  def bookmarkId: Option[ExternalId[Bookmark]] = (json \ "id").asOpt[String].flatMap(ExternalId.asOpt[Bookmark])

  private def readMatches(matches: JsValue): Seq[(Int, Int)] = {
    matches.asOpt[JsArray] map {
     case JsArray(pairs) => pairs.map { case JsArray(Seq(JsNumber(start), JsNumber(len))) => (start.toInt, (start + len).toInt) }
    } getOrElse(Seq.empty)
  }
}

object PersonalSearchHit extends Logging {
  def apply(json: JsValue) = new PersonalSearchHit(json)
  def apply(
    title: Option[String],
    url: String,
    titleMatches: Seq[(Int, Int)],
    urlMatches: Seq[(Int, Int)],
    collections: Option[Seq[ExternalId[Collection]]],
    bookmarkId: Option[ExternalId[Bookmark]]
  ): PersonalSearchHit = {
    try {
      var json = Json.obj(
        "title" -> title,
        "url" -> url
      )

      def addMatches(json: JsObject): JsObject = {
        var matchesJson = Json.obj()

        def add(name: String, matches: Seq[(Int, Int)]): JsObject = {
          if (matches.nonEmpty) {
            matchesJson + (name -> JsArray(matches.map(h => Json.arr(h._1, (h._2 - h._1)))))
          } else matchesJson
        }

        matchesJson = add("title", titleMatches)
        matchesJson = add("url", urlMatches)

        if (matchesJson.keys.size == 0) json else json + ("matches" -> matchesJson)
      }


      def addCollections(json: JsObject): JsObject = {
        collections match {
          case Some(collections) =>
            json + ("collections" -> Json.toJson(collections.map(_.id)))
          case None => json
        }
      }

      json = addMatches(json)
      json = addCollections(json)

      bookmarkId.foreach{ id => json = json + ("id" -> JsString(id.id)) }

      new PersonalSearchHit(json)
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize a hit [title=title][url=$url][titleMatches=$titleMatches][urlMatches=$urlMatches][collections=$collections][bookmarkId=$bookmarkId]", e)
        throw e
    }
  }
}
