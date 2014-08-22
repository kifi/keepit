package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.search._
import play.api.mvc.Action
import views.html
import com.keepit.model.User
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json._
import com.keepit.search.sharding.ShardSpecParser
import com.keepit.search.user.UserQueryParser
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.user.UserHit
import com.keepit.search.user.UserSearchResult
import com.keepit.search.user.UserSearchFilterFactory
import com.keepit.search.user.UserSearchRequest
import com.keepit.commanders.RemoteUserExperimentCommander
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.typeahead.PrefixFilter

class SearchController @Inject() (
    searcherFactory: MainSearcherFactory,
    userSearchFilterFactory: UserSearchFilterFactory,
    searchCommander: SearchCommander,
    userExperimentCommander: RemoteUserExperimentCommander) extends SearchServiceController {

  def distSearch() = Action(parse.tolerantJson) { request =>
    val json = request.body
    val shardSpec = (json \ "shards").as[String]
    val searchRequest = (json \ "request")

    // keep the following in sync with SearchServiceClientImpl
    val userId = (searchRequest \ "userId").as[Long]
    val lang1 = (searchRequest \ "lang1").as[String]
    val lang2 = (searchRequest \ "lang2").asOpt[String]
    val query = (searchRequest \ "query").as[String]
    val filter = (searchRequest \ "filter").asOpt[String]
    val maxHits = (searchRequest \ "maxHits").as[Int]
    val context = (searchRequest \ "context").asOpt[String]
    val debug = (searchRequest \ "debug").asOpt[String]

    val id = Id[User](userId)
    val userExperiments = Await.result(userExperimentCommander.getExperimentsByUser(id), 5 seconds)
    val shards = (new ShardSpecParser).parse[NormalizedURI](shardSpec)
    val result = searchCommander.distSearch(
      shards,
      id,
      Lang(lang1),
      lang2.map(Lang(_)),
      userExperiments,
      query,
      filter,
      maxHits,
      context,
      None,
      debug)

    Ok(result.json)
  }

  def distLangFreqs() = Action(parse.tolerantJson) { request =>
    val json = request.body
    val shardSpec = (json \ "shards").as[String]
    val userId = Id[User]((json \ "request").as[Long])
    val shards = (new ShardSpecParser).parse[NormalizedURI](shardSpec)
    Ok(Json.toJson(searchCommander.distLangFreqs(shards, userId).map { case (lang, freq) => lang.lang -> freq }))
  }

  //internal (from eliza/shoebox)
  def warmUpUser(userId: Id[User]) = Action { request =>
    searchCommander.warmUp(userId)
    Ok
  }

  def searchWithConfig() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val userId = Id[User]((js \ "userId").as[Long])
    val query = (js \ "query").as[String]
    val maxHits = (js \ "maxHits").as[Int]
    val predefinedConfig = (js \ "config").as[Map[String, String]]
    val res = searchCommander.search(userId, acceptLangs = Seq(), experiments = Set.empty, query = query, filter = None, maxHits = maxHits, lastUUIDStr = None, context = None, predefinedConfig = Some(SearchConfig(predefinedConfig)))
    Ok(JsArray(res.hits.map { x =>
      val id = x.uriId.id
      val title = x.bookmark.title.getOrElse("")
      val url = x.bookmark.url
      Json.obj("uriId" -> id, "title" -> title, "url" -> url)
    }))
  }

  def searchUsers() = Action(parse.tolerantJson) { request =>
    val UserSearchRequest(userId, queryText, maxHits, context, filter) = Json.fromJson[UserSearchRequest](request.body).get
    val searcher = searcherFactory.getUserSearcher
    val parser = new UserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val userFilter = filter match {
      case "f" if userId.isDefined => userSearchFilterFactory.friendsOnly(userId.get, Some(context))
      case "nf" if userId.isDefined => userSearchFilterFactory.nonFriendsOnly(userId.get, Some(context))
      case _ => userSearchFilterFactory.default(userId, Some(context))
    }
    val res = parser.parse(queryText) match {
      case None => UserSearchResult(Array.empty[UserHit], context)
      case Some(q) => searcher.search(q, maxHits, userFilter)
    }
    Ok(Json.toJson(res))
  }

  private def createFilter(userId: Option[Id[User]], filter: Option[String], context: Option[String]) = {
    filter match {
      case Some("f") => userSearchFilterFactory.friendsOnly(userId.get, context)
      case Some("nf") => userSearchFilterFactory.nonFriendsOnly(userId.get, context)
      case _ => userSearchFilterFactory.default(userId, context, excludeSelf = true) // may change this later
    }
  }

  def userTypeahead() = Action(parse.json) { request =>
    val UserSearchRequest(userIdOpt, queryText, maxHits, context, filter) = Json.fromJson[UserSearchRequest](request.body).get
    val userId = userIdOpt.get
    log.info(s"user search: userId = ${userId}")
    val excludedExperiments = Seq("fake") // TODO(yingjie): Address admins differently
    val searchFilter = createFilter(Some(userId), Some(filter), None)
    val searcher = searcherFactory.getUserSearcher
    val parser = new UserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val queryTerms = PrefixFilter.normalize(queryText).split("\\s+")
    val res = parser.parseWithUserExperimentConstrains(queryText, excludedExperiments) match {
      case None => UserSearchResult(Array.empty[UserHit], context = "")
      case Some(q) => searcher.searchPaging(q, searchFilter, 0, maxHits, queryTerms)
    }
    Ok(Json.toJson(res))
  }

  def sharingUserInfo(userId: Id[User]) = Action.async(parse.json) { implicit request =>
    SafeFuture {
      val uriIds = request.body.as[Seq[Long]].map(Id[NormalizedURI](_))
      val info = searchCommander.sharingUserInfo(userId, uriIds)
      Ok(Json.toJson(info))
    }
  }

  def explain(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: Option[String]) = Action { request =>
    val userExperiments = Await.result(userExperimentCommander.getExperimentsByUser(userId), 5 seconds)
    searchCommander.explain(userId, uriId, lang, userExperiments, query) match {
      case explanation if explanation.isDefined =>
        Ok(html.admin.explainResult(query, userId, uriId, explanation))
      case None =>
        Ok("shard not found")
    }
  }
}

