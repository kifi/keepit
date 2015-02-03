package com.keepit.search

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.search.user._
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.user._
import com.keepit.typeahead.PrefixFilter

@ImplementedBy(classOf[UserSearchCommanderImpl])
trait UserSearchCommander {
  def searchUsers(userSearchRequest: DeprecatedUserSearchRequest): DeprecatedUserSearchResult
  def searchUsers(userId: Option[Id[User]], queryText: String, maxHits: Int, context: Option[String], filter: Option[String], excludeSelf: Boolean): DeprecatedUserSearchResult
  def userTypeahead(userSearchRequest: DeprecatedUserSearchRequest, excludedExperiments: Seq[String]): DeprecatedUserSearchResult
  def userTypeahead(userId: Option[Id[User]], queryText: String, pageNum: Int, pageSize: Int, context: Option[String], filter: Option[String], excludedExperiments: Seq[String]): DeprecatedUserSearchResult
}

class UserSearchCommanderImpl @Inject() (
    userIndexer: UserIndexer,
    userSearchFilterFactory: DeprecatedUserSearchFilterFactory) extends UserSearchCommander with Logging {

  def searchUsers(userSearchRequest: DeprecatedUserSearchRequest): DeprecatedUserSearchResult = {
    val DeprecatedUserSearchRequest(userId, queryText, maxHits, context, filter) = userSearchRequest
    searchUsers(userId, queryText, maxHits, context = Some(context), filter = Some(filter), excludeSelf = false)
  }

  def searchUsers(userId: Option[Id[User]], queryText: String, maxHits: Int, context: Option[String], filter: Option[String], excludeSelf: Boolean): DeprecatedUserSearchResult = {
    val searcher = getUserSearcher
    val parser = new DeprecatedUserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val userFilter = createFilter(userId, filter, context, excludeSelf)
    parser.parse(queryText) match {
      case None => DeprecatedUserSearchResult(Array.empty[DeprecatedUserHit], context.getOrElse(""))
      case Some(q) => searcher.search(q, maxHits, userFilter)
    }
  }

  def userTypeahead(userSearchRequest: DeprecatedUserSearchRequest, excludedExperiments: Seq[String]): DeprecatedUserSearchResult = {
    val DeprecatedUserSearchRequest(userId, queryText, maxHits, context, filter) = userSearchRequest
    userTypeahead(userId, queryText, 0, maxHits, context = Some(context), filter = Some(filter), excludedExperiments = excludedExperiments)
  }

  def userTypeahead(userId: Option[Id[User]], queryText: String, pageNum: Int, pageSize: Int, context: Option[String], filter: Option[String], excludedExperiments: Seq[String]): DeprecatedUserSearchResult = {
    val searchFilter = createFilter(userId, filter, context, true)
    val searcher = getUserSearcher
    val parser = new DeprecatedUserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val queryTerms = PrefixFilter.normalize(queryText).split("\\s+")
    parser.parseWithUserExperimentConstrains(queryText, excludedExperiments) match {
      case None => DeprecatedUserSearchResult(Array.empty[DeprecatedUserHit], context = "")
      case Some(q) => searcher.searchPaging(q, searchFilter, pageNum, pageSize, queryTerms)
    }
  }

  private def getUserSearcher = new DeprecatedUserSearcher(userIndexer.getSearcher)

  private def createFilter(userId: Option[Id[User]], filter: Option[String], context: Option[String], excludeSelf: Boolean) = {
    filter match {
      case Some("f") if userId.isDefined => userSearchFilterFactory.friendsOnly(userId.get, context)
      case Some("nf") if userId.isDefined => userSearchFilterFactory.nonFriendsOnly(userId.get, context)
      case Some("non-f") if userId.isDefined => userSearchFilterFactory.nonFriendsOnly(userId.get, context)
      case _ => userSearchFilterFactory.default(userId, context, excludeSelf) // may change this later
    }
  }
}
