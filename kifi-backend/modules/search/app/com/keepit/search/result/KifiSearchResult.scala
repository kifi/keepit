package com.keepit.search.result

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.net.URISanitizer
import com.keepit.model._
import com.keepit.search.engine.result.KifiShardHit
import com.keepit.search.{ Lang, ArticleSearchResult, Scoring, SearchConfigExperiment }
import com.keepit.common.json.TraversableFormat
import com.keepit.social.BasicUser
import play.api.libs.json._
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import scala.math.BigDecimal.double2bigDecimal
import scala.math.BigDecimal.int2bigDecimal
import scala.math.BigDecimal.long2bigDecimal
import com.keepit.search.index.{ Analyzer, DefaultAnalyzer }

class KifiSearchResult(val json: JsObject) extends AnyVal {
}

object KifiSearchResult extends Logging {
  def v1(
    uuid: ExternalId[ArticleSearchResult],
    query: String,
    hits: Seq[KifiSearchHit],
    myTotal: Int,
    friendsTotal: Int,
    othersTotal: Int,
    mayHaveMoreHits: Boolean,
    show: Boolean,
    cutPoint: Int,
    experimentId: Option[Id[SearchConfigExperiment]],
    context: String,
    collections: Seq[ExternalId[Collection]]): KifiSearchResult = {
    try {
      new KifiSearchResult(JsObject(List(
        "uuid" -> JsString(uuid.toString),
        "query" -> JsString(query),
        "hits" -> JsArray(hits.map(_.json)),
        "myTotal" -> JsNumber(myTotal),
        "friendsTotal" -> JsNumber(friendsTotal),
        "othersTotal" -> JsNumber(othersTotal),
        "mayHaveMore" -> JsBoolean(mayHaveMoreHits),
        "show" -> JsBoolean(show),
        "cutPoint" -> JsNumber(cutPoint),
        "experimentId" -> experimentId.map(id => JsNumber(id.id)).getOrElse(JsNull),
        "context" -> JsString(context),
        "experts" -> JsArray()
      )))
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize KifiSearchResult [uuid=$uuid][query=$query][hits=$hits][mayHaveMore=$mayHaveMoreHits][show=$show][experimentId=$experimentId][context=$context]", e)
        throw e
    }
  }

  def v2(
    uuid: ExternalId[ArticleSearchResult],
    query: String,
    lang: Lang,
    hits: Seq[KifiShardHit],
    myTotal: Int,
    friendsTotal: Int,
    mayHaveMoreHits: Boolean,
    show: Boolean,
    cutPoint: Int,
    experimentId: Option[Id[SearchConfigExperiment]],
    context: String): KifiSearchResult = {
    try {
      new KifiSearchResult(JsObject(List(
        "uuid" -> JsString(uuid.toString),
        "query" -> JsString(query),
        "hits" -> toKifiSearchHitsV2(query, lang, hits),
        "myTotal" -> JsNumber(myTotal),
        "friendsTotal" -> JsNumber(friendsTotal),
        "mayHaveMore" -> JsBoolean(mayHaveMoreHits),
        "show" -> JsBoolean(show),
        "cutPoint" -> JsNumber(cutPoint),
        "experimentId" -> experimentId.map(id => JsNumber(id.id)).getOrElse(JsNull),
        "context" -> JsString(context)
      )))
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize KifiPlainResult [uuid=$uuid][query=$query][hits=$hits][mayHaveMore=$mayHaveMoreHits][show=$show][cutPoint=$cutPoint][experimentId=$experimentId][context=$context]", e)
        throw e
    }
  }

  def toKifiSearchHitsV2(query: String, lang: Lang, hits: Seq[KifiShardHit]): JsArray = {
    val analyzer = DefaultAnalyzer.getAnalyzerWithStemmer(lang)
    val terms = Highlighter.getQueryTerms(query, analyzer)
    val v2Hits = hits.map { h =>
      var json = JsObject(List(
        "title" -> h.titleJson,
        "url" -> h.urlJson
      ))
      h.externalIdJson.asOpt[JsString].foreach { v => json = json + ("keepId" -> v) }
      getMatches(analyzer, terms, h).foreach { matches => json = json + ("matches" -> matches) }
      json
    }
    JsArray(v2Hits)
  }

  def uriSummaryInfoV2(uriSummaries: Seq[Option[URISummary]]): JsObject = {
    val v2Infos = uriSummaries.map {
      case Some(uriSummary) => JsObject(List("uriSummary" -> Json.toJson(uriSummary)))
      case None => JsObject(List())
    }
    JsObject(List(
      "hits" -> JsArray(v2Infos)
    ))
  }

  def getMatches(analyzer: Analyzer, terms: Set[String], hit: KifiShardHit): Option[JsObject] = {
    var matchesJson = Json.obj()

    def add(name: String, content: String) = {
      val matches = Highlighter.highlight(content, analyzer, "", terms)
      if (matches.nonEmpty) { matchesJson = matchesJson + (name -> Highlighter.formatMatches(matches)) }
    }

    add("title", hit.title)
    add("url", hit.url)
    Some(matchesJson).filter(_.keys.nonEmpty)
  }
}

class KifiSearchHit(val json: JsObject) extends AnyVal {
  def isMyBookmark: Boolean = (json \ "isMyBookmark").as[Boolean]
  def isPrivate: Boolean = (json \ "isPrivate").as[Boolean]
  def count: Int = (json \ "count").as[Int]
  def users: Seq[BasicUser] = TraversableFormat.seq[BasicUser].reads(json \ "users").get
  def score: Float = (json \ "score").as[Float]
  def bookmark: BasicSearchHit = new BasicSearchHit((json \ "bookmark").as[JsObject])

  override def toString(): String = json.toString()
}

object KifiSearchHit extends Logging {
  def apply(json: JsObject): KifiSearchHit = new KifiSearchHit(json)
  def apply(
    hit: BasicSearchHit,
    count: Int, // public bookmark count
    isMyBookmark: Boolean,
    isPrivate: Boolean,
    users: Seq[BasicUser],
    score: Float): KifiSearchHit = {
    try {
      new KifiSearchHit(JsObject(List(
        "count" -> JsNumber(count),
        "bookmark" -> hit.json,
        "users" -> Json.toJson(users),
        "score" -> JsNumber(score.toDouble),
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

class PartialSearchResult(val json: JsValue) extends AnyVal {
  def hits: Seq[DetailedSearchHit] = (json \ "hits").as[JsArray] match {
    case JsArray(hits) => hits.map { json => new DetailedSearchHit(json.as[JsObject]) }
    case _ => Seq.empty
  }
  def myTotal: Int = (json \ "myTotal").as[Int]
  def friendsTotal: Int = (json \ "friendsTotal").as[Int]
  def othersTotal: Int = (json \ "othersTotal").as[Int]
  def friendStats: FriendStats = (json \ "friendStats").as[FriendStats]
  def show: Boolean = (json \ "show").as[Boolean] // TODO: remove
  def cutPoint: Int = (json \ "cutPoint").as[Int]
}

object PartialSearchResult extends Logging {
  def apply(
    hits: Seq[DetailedSearchHit],
    myTotal: Int,
    friendsTotal: Int,
    othersTotal: Int,
    friendStats: FriendStats,
    show: Boolean, // TODO: remove
    cutPoint: Int): PartialSearchResult = {
    try {
      new PartialSearchResult(JsObject(List(
        "hits" -> JsArray(hits.map(_.json)),
        "myTotal" -> JsNumber(myTotal),
        "friendsTotal" -> JsNumber(friendsTotal),
        "othersTotal" -> JsNumber(othersTotal),
        "friendStats" -> Json.toJson(friendStats),
        "show" -> JsBoolean(show), // TODO: remove
        "cutPoint" -> JsNumber(cutPoint)
      )))
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize PartialSearchResult [hits=$hits][myTotal=$myTotal][friendsTotal=$friendsTotal][othersTotal=$othersTotal][friendStats=$friendStats]", e)
        throw e
    }
  }
  lazy val empty = {
    new PartialSearchResult(JsObject(List(
      "hits" -> JsArray(),
      "myTotal" -> JsNumber(0),
      "friendsTotal" -> JsNumber(0),
      "othersTotal" -> JsNumber(0),
      "friendsStats" -> Json.toJson(FriendStats.empty),
      "show" -> JsBoolean(false) // TODO: remove
    )))
  }
}

class DetailedSearchHit(val json: JsObject) extends AnyVal {
  def uriId: Id[NormalizedURI] = Id[NormalizedURI]((json \ "uriId").as[Long])
  def uriSummary: Option[URISummary] = (json \ "uriSummary").asOpt[URISummary]
  def isMyBookmark: Boolean = (json \ "isMyBookmark").as[Boolean]
  def isFriendsBookmark: Boolean = (json \ "isFriendsBookmark").as[Boolean]
  def isPrivate: Boolean = (json \ "isPrivate").as[Boolean]
  def bookmarkCount: Int = (json \ "bookmarkCount").as[Int]
  def users: Seq[Id[User]] = (json \ "users").asOpt[Seq[Long]].map { users => users.map { id => Id[User](id.toLong) } }.getOrElse(Seq.empty)
  def score: Float = (json \ "score").as[Float]
  def textScore: Float = (json \ "textScore").asOpt[Float].getOrElse(-1f)
  def scoring: Scoring = (json \ "scoring").as[Scoring]
  def bookmark: BasicSearchHit = new BasicSearchHit((json \ "bookmark").as[JsObject])

  def sanitized: DetailedSearchHit = {
    set("bookmark", bookmark.sanitized.json)
  }

  def set(key: String, value: JsValue): DetailedSearchHit = {
    new DetailedSearchHit((json - key) + (key -> value))
  }

  override def toString(): String = json.toString()
}

object DetailedSearchHit extends Logging {
  def apply(
    uriId: Long,
    bookmarkCount: Int, // public bookmark count
    hit: BasicSearchHit,
    isMyBookmark: Boolean,
    isFriendsBookmark: Boolean,
    isPrivate: Boolean,
    users: Seq[Id[User]],
    score: Float,
    textScore: Float,
    scoring: Scoring): DetailedSearchHit = {
    try {
      new DetailedSearchHit(JsObject(List(
        "uriId" -> JsNumber(uriId),
        "bookmarkCount" -> JsNumber(bookmarkCount),
        "bookmark" -> hit.json,
        "users" -> JsArray(users.map(id => JsNumber(id.id))),
        "score" -> JsNumber(score.toDouble),
        "textScore" -> JsNumber(textScore.toDouble),
        "scoring" -> Json.toJson(scoring),
        "isMyBookmark" -> JsBoolean(isMyBookmark),
        "isFriendsBookmark" -> JsBoolean(isFriendsBookmark),
        "isPrivate" -> JsBoolean(isPrivate)
      )))
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize DetailedSearchHit [hit=$hit][bookmarkCount=$bookmarkCount][users=$users][scoring=$scoring][isMybookmark=$isMyBookmark][isFriendsBookmark=$isFriendsBookmark][isPrivate=$isPrivate]", e)
        throw e
    }
  }
}

class BasicSearchHit(val json: JsObject) extends AnyVal {
  def title: Option[String] = (json \ "title").asOpt[String].filter(_.nonEmpty)
  def url: String = (json \ "url").as[String]
  def titleMatches: Seq[(Int, Int)] = readMatches(json \ "matches" \ "title")
  def urlMatches: Seq[(Int, Int)] = readMatches(json \ "matches" \ "url")
  def collections: Option[Seq[String]] = (json \ "tags").asOpt[JsArray].map { case JsArray(ids) => ids.map(id => id.as[String]) } // were ExternalId[Collection], moving to inlined hashtags
  def bookmarkId: Option[ExternalId[Keep]] = (json \ "id").asOpt[String].flatMap(ExternalId.asOpt[Keep])

  def addMatches(titleMatches: Option[Seq[(Int, Int)]], urlMatches: Option[Seq[(Int, Int)]]): BasicSearchHit = {
    var matchesJson = Json.obj()

    def add(name: String, matchesOpt: Option[Seq[(Int, Int)]]) = {
      matchesOpt.foreach { matches =>
        if (matches.nonEmpty) { matchesJson = matchesJson + (name -> Highlighter.formatMatches(matches)) }
      }
    }

    add("title", titleMatches)
    add("url", urlMatches)

    if (matchesJson.keys.size == 0) this else new BasicSearchHit(json + ("matches" -> matchesJson))
  }

  def addCollections(collections: Seq[ExternalId[Collection]]): BasicSearchHit = {
    if (collections.isEmpty) this else new BasicSearchHit(json + ("tags" -> Json.toJson(collections.map(_.id))))
  }

  def sanitized: BasicSearchHit = {
    val rawURL = url
    val sanitizedURL = URISanitizer.sanitize(url)
    if (rawURL == sanitizedURL) this else set("url", JsString(sanitizedURL))
    set("url", JsString(sanitizedURL))
  }

  def set(key: String, value: JsValue): BasicSearchHit = {
    new BasicSearchHit((json - key) + (key -> value))
  }

  private def readMatches(matches: JsValue): Seq[(Int, Int)] = {
    matches.asOpt[JsArray] map {
      case JsArray(pairs) => pairs.map { case JsArray(Seq(JsNumber(start), JsNumber(len))) => (start.toInt, (start + len).toInt) }
    } getOrElse (Seq.empty)
  }
}

object BasicSearchHit extends Logging {
  def apply(
    title: Option[String],
    url: String,
    collections: Option[Seq[ExternalId[Collection]]] = None,
    bookmarkId: Option[ExternalId[Keep]] = None,
    titleMatches: Option[Seq[(Int, Int)]] = None,
    urlMatches: Option[Seq[(Int, Int)]] = None): BasicSearchHit = {
    try {
      var json = Json.obj(
        "title" -> title,
        "url" -> url
      )
      bookmarkId.foreach { id => json = json + ("id" -> JsString(id.id)) }

      var h = new BasicSearchHit(json)
      h = h.addMatches(titleMatches, urlMatches)
      collections.foreach { c => h = h.addCollections(c) }
      h
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize a hit [title=title][url=$url][titleMatches=$titleMatches][urlMatches=$urlMatches][collections=$collections][bookmarkId=$bookmarkId]", e)
        throw e
    }
  }
}

