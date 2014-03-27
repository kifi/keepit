package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.search._
import play.api.mvc.Action
import views.html
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.model.User
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json._
import com.keepit.search.user.UserQueryParser
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.user.UserHit
import com.keepit.search.user.UserSearchResult
import com.keepit.search.user.UserSearchFilterFactory
import com.keepit.search.user.UserSearchRequest
import com.keepit.commanders.RemoteUserExperimentCommander
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.search.sharding.ActiveShards
import com.keepit.typeahead.PrefixFilter

class SearchController @Inject()(
    shards: ActiveShards,
    searchConfigManager: SearchConfigManager,
    searcherFactory: MainSearcherFactory,
    userSearchFilterFactory: UserSearchFilterFactory,
    shoeboxClient: ShoeboxServiceClient,
    airbrake: AirbrakeNotifier,
    searchCommander: SearchCommander,
    userExperimentCommander: RemoteUserExperimentCommander
  ) extends SearchServiceController {

  //internal (from eliza/shoebox)
  def warmUpUser(userId: Id[User]) = Action { request =>
    SafeFuture {
      searcherFactory.warmUp(userId)
    }
    Ok
  }

  def searchKeeps(userId: Id[User], query: String) = Action { request =>
    val uris = shards.shards.foldLeft(Set.empty[Long]){ (uris, shard) =>
      val searcher = searcherFactory.bookmarkSearcher(shard, userId)
      uris ++ searcher.search(query, Lang("en"))
    }
    Ok(JsArray(uris.toSeq.map(JsNumber(_))))
  }

  def searchWithConfig() = Action(parse.tolerantJson){ request =>
    val js = request.body
    val userId = Id[User]((js \ "userId").as[Long])
    val query = (js \ "query").as[String]
    val maxHits = (js \ "maxHits").as[Int]
    val predefinedConfig = (js \ "config").as[Map[String, String]]
    val res = searchCommander.search(userId, acceptLangs = Seq(), experiments = Set.empty, query = query, filter = None, maxHits = maxHits, lastUUIDStr = None, context = None, predefinedConfig = Some(SearchConfig(predefinedConfig)), start = None, end = None, tz = None, coll = None)
    Ok(JsArray(res.hits.map{ x =>
      val id = x.uriId.id
      val title = x.bookmark.title.getOrElse("")
      val url = x.bookmark.url
      Json.obj("uriId" -> id, "title" -> title, "url" -> url )
    }))
  }

  def searchUsers() = Action(parse.tolerantJson){ request =>
    val UserSearchRequest(userId, queryText, maxHits, context, filter) = Json.fromJson[UserSearchRequest](request.body).get
    val searcher = searcherFactory.getUserSearcher
    val parser = new UserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val userFilter = filter match {
      case "f" if userId.isDefined => userSearchFilterFactory.friendsOnly(userId.get, Some(context))
      case "nf"if userId.isDefined => userSearchFilterFactory.nonFriendsOnly(userId.get, Some(context))
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
      case _ => userSearchFilterFactory.default(userId, context, excludeSelf = true)      // may change this later
    }
  }

  def userTypeahead() = Action(parse.json){ request =>
    val UserSearchRequest(userIdOpt, queryText, maxHits, context, filter) = Json.fromJson[UserSearchRequest](request.body).get
    val userId = userIdOpt.get
    log.info(s"user search: userId = ${userId}")
    val excludedExperiments = Seq("fake")   // TODO(yingjie): Address admins differently
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

  def explain(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: Option[String]) = Action { request =>
    val userExperiments = Await.result(userExperimentCommander.getExperimentsByUser(userId), 5 seconds)
    val (config, _) = searchConfigManager.getConfig(userId, userExperiments)
    val langs = lang match {
      case Some(str) => str.split(",").toSeq.map(Lang(_))
      case None => Seq(Lang("en"))
    }

    shards.find(uriId) match {
      case Some(shard) =>
        val searcher = searcherFactory(shard, userId, query, langs(0), if (langs.size > 1) Some(langs(1)) else None, 0, SearchFilter.default(), config)
        val explanation = searcher.explain(uriId)
        Ok(html.admin.explainResult(query, userId, uriId, explanation))
      case None =>
        Ok("shard not found")
    }
  }
}

