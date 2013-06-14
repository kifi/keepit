package com.keepit.common.routes

import com.keepit.common.db.ExternalId
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.db.State
import com.keepit.model.NormalizedURI
import com.keepit.model.Collection
import com.keepit.search.SearchConfigExperiment
import com.keepit.model.ExperimentType
import com.keepit.model.UserSession


trait Service
case class ServiceRoute(method: Method, path: String, params: Param*) {
  override def toString = path + (if(params.nonEmpty) params.map({ p =>
    p.key + (if(p.value.value != "") "=" + p.value.value else "")
  }).mkString("&") else "")
}

case class Param(key: String, value: ParamValue = ParamValue(""))
case class ParamValue(value: String)
object ParamValue {
  implicit def stringToParam(i: String) = ParamValue(i)
  implicit def longToParam(i: Long) = ParamValue(i.toString)
  implicit def intToParam(i: Int) = ParamValue(i.toString)
  implicit def stateToParam[T](i: State[T]) = ParamValue(i.value)
  implicit def externalIdToParam[T](i: ExternalId[T]) = ParamValue(i.id)
  implicit def idToParam[T](i: Id[T]) = ParamValue(i.id.toString)
}

abstract class Method(name: String)
case object GET extends Method("GET")
case object POST extends Method("POST")
case object PUT extends Method("PUT")

/*
  Hello future FortyTwoers. This will help you generate these (still need to handle params, but saves 80% of time):
    val regex = """(POST|GET)\s*(/internal\S*)[\s].*\.(.*)""".r
    def convert(s: String) = s match {
      case regex(a,b, c) => println(s"""def $c = ServiceRoute($a, "$b")""")
    }
    val all = """ ....... """ // (copy from Play routes)
    all.split("\n").filter(_.nonEmpty).map(convert)
 */

object shoebox extends Service {
  object service {
    def getNormalizedURI(id: Long) = ServiceRoute(GET, "/internal/shoebox/database/getNormalizedURI", Param("id", id))
    def getNormalizedURIs(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/getNormalizedURIs", Param("ids", ids))
    def getUsers(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/getUsers", Param("ids", ids))
    def getUserIdsByExternalIds(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/userIdsByExternalIds", Param("ids", ids))
    def getBasicUsers(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/getBasicUsers", Param("ids", ids))
    def getCollectionIdsByExternalIds(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/collectionIdsByExternalIds", Param("ids", ids))
    def getUserOpt(id: ExternalId[User]) = ServiceRoute(GET, "/internal/shoebox/database/getUserOpt", Param("id", id))
    def getUserExperiments(id: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getUserExperiments", Param("id", id))
    def getConnectedUsers(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getConnectedUsers", Param("userId", userId))
    def getBrowsingHistoryFilter(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/tracker/browsingHistory", Param("userId", userId))
    def getClickHistoryFilter(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/tracker/clickHistory", Param("userId", userId))
    def getBookmarks(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/bookmark", Param("userId", userId))
    def getBookmarksChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedBookmark", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/bookmarkByUriUser", Param("uriId", uriId), Param("userId", userId))
    def getUsersChanged(seqNum: Long) = ServiceRoute(GET, "/internal/shoebox/database/changedUser", Param("seqNum", seqNum))
    def persistServerSearchEvent() = ServiceRoute(POST, "/internal/shoebox/persistServerSearchEvent")
    def sendMail() = ServiceRoute(POST, "/internal/shoebox/database/sendMail")
    def getPhrasesByPage(page: Int, size: Int) = ServiceRoute(GET, "/internal/shoebox/database/getPhrasesByPage", Param("page", page), Param("size", size))
    def getCollectionsChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getCollectionsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getBookmarksInCollection(collectionId: Id[Collection]) = ServiceRoute(GET, "/internal/shoebox/database/getBookmarksInCollection", Param("collectionId", collectionId))
    def getCollectionsByUser(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getCollectionsByUser", Param("userId", userId))
    def getIndexable(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexable", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getPersonalSearchInfo(userId: Id[User], allUsers: String, formattedHits: String) = ServiceRoute(GET, "/internal/shoebox/database/personalSearchInfo", Param("userId", userId), Param("allUsers", allUsers), Param("formattedHits", formattedHits))
    def getActiveExperiments() = ServiceRoute(GET, "/internal/shoebox/database/getActiveExperiments")
    def getExperiments() = ServiceRoute(GET, "/internal/shoebox/database/getExperiments")
    def getExperiment(id: Id[SearchConfigExperiment]) = ServiceRoute(GET, "/internal/shoebox/database/getExperiment", Param("id", id))
    def saveExperiment = ServiceRoute(POST, "/internal/shoebox/database/saveExperiment")
    def hasExperiment(userId: Id[User], state: State[ExperimentType]) = ServiceRoute(GET, "/internal/shoebox/database/hasExperimenthas", Param("userId", userId), Param("state", state))
    def reportArticleSearchResult() = ServiceRoute(POST, "/internal/shoebox/database/reportArticleSearchResult")
    def getSocialUserInfoByNetworkAndSocialId(id: String, networkType: String) = ServiceRoute(GET, "/internal/shoebox/database/socialUserInfoByNetworkAndSocialId", Param("id", id), Param("networkType", networkType))
    def getSocialUserInfosByUserId(id: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/socialUserInfosByUserId", Param("id", id))
    def getSessionByExternalId(sessionId: ExternalId[UserSession]) = ServiceRoute(GET, "/internal/shoebox/database/sessionByExternalId", Param("sessionId", sessionId))
  }
}

object search extends Service {
  object service {
    def logResultClicked() = ServiceRoute(POST, "/internal/search/events/resultClicked")
    def uriGraphInfo() = ServiceRoute(GET, "/internal/search/uriGraph/info")
    def sharingUserInfo(userId: Id[User], uriIds: String) = ServiceRoute(GET, "/internal/search/uriGraph/sharingUserInfo", Param("userId", userId), Param("uriIds", uriIds))
    def updateURIGraph() = ServiceRoute(POST, "/internal/search/uriGraph/update")
    def uriGraphReindex() = ServiceRoute(POST, "/internal/search/uriGraph/reindex")
    def dumpLuceneDocument(id: Id[User]) = ServiceRoute(POST, "/internal/search/uriGraph/dumpDoc/:id", Param("id", id))
    def indexInfo() = ServiceRoute(GET, "/internal/search/index/info")
    def searchUpdate() = ServiceRoute(POST, "/internal/search/index/update")
    def searchReindex() = ServiceRoute(POST, "/internal/search/index/reindex")
    def getSequenceNumber() = ServiceRoute(GET, "/internal/search/index/sequenceNumber")
    def refreshSearcher() = ServiceRoute(POST, "/internal/search/index/refreshSearcher")
    def refreshPhrases() = ServiceRoute(POST, "/internal/search/index/refreshPhrases")
    def dumpLuceneDocument(id: Id[NormalizedURI]) = ServiceRoute(POST, "/internal/search/index/dumpDoc/:id", Param("id", id))
  }
}

