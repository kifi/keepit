package com.keepit.search.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.search._
import com.keepit.typeahead.{ UserPrefixSearchHit, TypeaheadHit, UserPrefixSearchRequest }
import play.api.mvc.Action
import views.html
import com.keepit.model.User
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import play.api.libs.json._
import com.keepit.search.index.sharding.ShardSpecParser
import com.keepit.search.user.DeprecatedUserSearchRequest
import com.keepit.commanders.RemoteUserExperimentCommander
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.routes.Search
import com.keepit.search.augmentation.{ AugmentableItem, ItemAugmentationRequest, AugmentationCommander }
import com.keepit.search.controllers.util.SearchControllerUtil

class SearchController @Inject() (
    uriSearchCommander: UriSearchCommander,
    augmentationCommander: AugmentationCommander,
    languageCommander: LanguageCommander,
    librarySearchCommander: LibrarySearchCommander,
    userSearchCommander: UserSearchCommander,
    distributedSearchClient: DistributedSearchServiceClient,
    userExperimentCommander: RemoteUserExperimentCommander) extends SearchServiceController {

  def distSearchUris() = Action.async(parse.tolerantJson) { request =>
    val json = request.body
    val shardSpec = (json \ "shards").as[String]
    val shards = (new ShardSpecParser).parse[NormalizedURI](shardSpec)
    val uriSearchRequest = (json \ "request").as[UriSearchRequest]

    uriSearchCommander.distSearchUris(shards, uriSearchRequest).map { result =>
      Ok(result.json)
    }
  }

  def distLangFreqs() = Action.async(parse.tolerantJson) { request =>
    val json = request.body
    val shardSpec = (json \ "shards").as[String]
    val shards = (new ShardSpecParser).parse[NormalizedURI](shardSpec)
    val searchRequest = (json \ "request")

    val userId = (searchRequest \ "userId").as[Id[User]]
    val libraryContext = (searchRequest \ "library").asOpt[LibraryScope]
    languageCommander.distLangFreqs(shards, userId, libraryContext).map { freqs =>
      Ok(Json.toJson(freqs.map { case (lang, freq) => lang.lang -> freq }))
    }
  }

  def distAugmentation() = Action.async(parse.tolerantJson) { request =>
    val json = request.body
    val shardSpec = (json \ "shards").as[String]
    val shards = (new ShardSpecParser).parse[NormalizedURI](shardSpec)
    val augmentationRequest = (json \ "request").as[ItemAugmentationRequest]
    augmentationCommander.distAugmentation(shards, augmentationRequest).map { augmentationResponse =>
      Ok(Json.toJson(augmentationResponse))
    }
  }

  def distSearchLibraries() = Action.async(parse.tolerantJson) { request =>
    val json = request.body
    val shardSpec = (json \ "shards").as[String]
    val shards = (new ShardSpecParser).parse[NormalizedURI](shardSpec)
    val librarySearchRequest = (json \ "request").as[LibrarySearchRequest]
    librarySearchCommander.distSearchLibraries(shards, librarySearchRequest).map { libraryShardResults =>
      Ok(Json.toJson(libraryShardResults))
    }
  }

  def distSearchUsers() = Action.async(parse.tolerantJson) { request =>
    val json = request.body
    val shardSpec = (json \ "shards").as[String]
    val shards = (new ShardSpecParser).parse[NormalizedURI](shardSpec)
    val userSearchRequest = (json \ "request").as[UserSearchRequest]
    userSearchCommander.distSearchUsers(shards, userSearchRequest).map { userShardResults =>
      Ok(Json.toJson(userShardResults))
    }
  }

  //internal (from eliza/shoebox)
  def warmUpUser(userId: Id[User]) = Action { request =>
    uriSearchCommander.warmUp(userId)
    Ok
  }

  def searchUsers() = Action(parse.tolerantJson) { request =>
    val userSearchRequest = Json.fromJson[DeprecatedUserSearchRequest](request.body).get
    val res = userSearchCommander.searchUsers(userSearchRequest)
    Ok(Json.toJson(res))
  }

  def searchUsersByName() = Action.async(parse.tolerantJson) { request =>
    val searchRequest = request.body.as[UserPrefixSearchRequest]
    val filter = SearchFilter.empty
    val contextFut = Future.successful(SearchContext(None, orderBy = SearchRanking.relevancy, filter = filter, disablePrefixSearch = false, disableFullTextSearch = true))
    userSearchCommander.searchUsers(userId = searchRequest.userId, acceptLangs = searchRequest.acceptLangs, experiments = searchRequest.userExperiments, query = searchRequest.query, contextFuture = contextFut, maxHits = searchRequest.maxHits, explain = None).map { userSearchResult =>
      val userIdsAndScores = userSearchResult.hits.map(hit => UserPrefixSearchHit(hit.id, hit.score))
      Ok(Json.toJson(userIdsAndScores))
    }
  }

  def userTypeahead() = Action(parse.json) { request =>
    val userSearchRequest = Json.fromJson[DeprecatedUserSearchRequest](request.body).get
    val res = userSearchCommander.userTypeahead(userSearchRequest, excludedExperiments = Seq("fake")) // TODO(yingjie): Address admins differently
    Ok(Json.toJson(res))
  }

  def explainUriResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], libraryId: Option[Long], lang: Option[String], debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean) = Action.async { request =>
    val libId = libraryId.map(Id[Library](_))
    if (uriSearchCommander.findShard(uriId).isDefined) {
      val userExperiments = Await.result(userExperimentCommander.getExperimentsByUser(userId), 5 seconds)
      uriSearchCommander.explain(userId, uriId, libId, lang, userExperiments, query, debug, disablePrefixSearch, disableFullTextSearch) map { explanationOpt =>
        Ok(html.admin.explainUriResult(userId, uriId, explanationOpt))
      }
    } else {
      distributedSearchClient.call(userId, uriId, Search.internal.explainUriResult(query, userId, uriId, libId, lang, debug, disablePrefixSearch, disableFullTextSearch), JsNull).map(r => Ok(r.body))
    }
  }

  def explainLibraryResult(query: String, userId: Id[User], libraryId: Id[Library], acceptLangs: String, debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean) = Action.async { request =>
    val userExperiments = Await.result(userExperimentCommander.getExperimentsByUser(userId), 5 seconds)
    val langs = acceptLangs.split(",").filter(_.nonEmpty)
    val context = SearchContext(None, SearchRanking.default, SearchFilter.empty, disablePrefixSearch, disableFullTextSearch)
    librarySearchCommander.searchLibraries(userId, langs, userExperiments, query, Future.successful(context), 1, None, debug, Some(libraryId)).map { result =>
      Ok(html.admin.explainLibraryResult(userId, libraryId, result.explanation))
    }
  }

  def explainUserResult(query: String, userId: Id[User], resultUserId: Id[User], acceptLangs: String, debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean) = Action.async { request =>
    val userExperiments = Await.result(userExperimentCommander.getExperimentsByUser(userId), 5 seconds)
    val langs = acceptLangs.split(",").filter(_.nonEmpty)
    val context = SearchContext(None, SearchRanking.default, SearchFilter.empty, disablePrefixSearch, disableFullTextSearch)
    userSearchCommander.searchUsers(userId, langs, userExperiments, query, Future.successful(context), 1, None, debug, Some(resultUserId)).map { result =>
      Ok(html.admin.explainUserResult(userId, resultUserId, result.explanation))
    }
  }

  def augmentation() = Action.async(parse.json) { implicit request =>
    augmentationCommander.augmentation(request.body.as[ItemAugmentationRequest]).map { augmentationResponse =>
      val json = Json.toJson(augmentationResponse)
      Ok(json)
    }
  }

  def augment() = Action.async(parse.json) { implicit request =>
    // This should stay in sync with SearchServiceClient.augment
    val userId = (request.body \ "userId").asOpt[Id[User]]
    val maxKeepersShown = (request.body \ "maxKeepersShown").as[Int]
    val maxLibrariesShown = (request.body \ "maxLibrariesShown").as[Int]
    val maxTagsShown = (request.body \ "maxTagsShown").as[Int]
    val items = (request.body \ "items").as[Seq[AugmentableItem]]
    val showPublishedLibrariesOpt = (request.body \ "showPublishedLibraries").asOpt[Boolean]

    val itemAugmentationRequest = ItemAugmentationRequest.uniform(userId getOrElse SearchControllerUtil.nonUser, items: _*).copy(showPublishedLibraries = showPublishedLibrariesOpt)
    augmentationCommander.getAugmentedItems(itemAugmentationRequest).map { augmentedItems =>
      val infos = items.map(augmentedItems(_).toLimitedAugmentationInfo(maxKeepersShown, maxLibrariesShown, maxTagsShown))
      val result = Json.toJson(infos)
      Ok(result)
    }
  }
}

