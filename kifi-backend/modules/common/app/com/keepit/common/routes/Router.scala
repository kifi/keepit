package com.keepit.common.routes

import com.keepit.common.db.ExternalId
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.db.State
import com.keepit.model.NormalizedURI
import com.keepit.model.Collection
import com.keepit.model.Comment
import com.keepit.search.SearchConfigExperiment
import com.keepit.model.ExperimentType
import com.keepit.model.UserSession
import java.net.URLEncoder
import com.keepit.common.strings.UTF8


trait Service
case class ServiceRoute(method: Method, path: String, params: Param*) {
  def url = path + (if(params.nonEmpty) "?" + params.map({ p =>
    URLEncoder.encode(p.key, UTF8) + (if(p.value.value != "") "=" + URLEncoder.encode(p.value.value, UTF8) else "")
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
  implicit def optionToParam[T](i: Option[T])(implicit e: T => ParamValue) = if(i.nonEmpty) e(i.get) else ParamValue("")
}

abstract class Method(name: String)
case object GET extends Method("GET")
case object POST extends Method("POST")
case object PUT extends Method("PUT")

/*
  Hello future FortyTwoers. This will help you generate these (still need to handle params, but saves 80% of time):
    val regex = """(POST|GET)\s*(/internal\S*)[\s].*\.(.*)""".r
    def convert(s: String) = s match {
      case regex(a,b,c) => println(s"""def $c = ServiceRoute($a, "$b")""")
    }
    val all = """ ....... """ // (copy from Play routes)
    all.split("\n").filter(_.nonEmpty).map(convert)
 */

object Shoebox extends Service {
  object internal {
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
    def getCommentsChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedComment", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getCommentRecipientIds(commentId: Id[Comment]) = ServiceRoute(GET, "/internal/shoebox/database/commentRecipientIds", Param("commentId", commentId))
    def persistServerSearchEvent() = ServiceRoute(POST, "/internal/shoebox/persistServerSearchEvent")
    def sendMail() = ServiceRoute(POST, "/internal/shoebox/database/sendMail")
    def getPhrasesByPage(page: Int, size: Int) = ServiceRoute(GET, "/internal/shoebox/database/getPhrasesByPage", Param("page", page), Param("size", size))
    def getCollectionsChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getCollectionsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getBookmarksInCollection(collectionId: Id[Collection]) = ServiceRoute(GET, "/internal/shoebox/database/getBookmarksInCollection", Param("collectionId", collectionId))
    def getCollectionsByUser(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getCollectionsByUser", Param("userId", userId))
    def getIndexable(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexable", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
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

object Search extends Service {
  object internal {
    def logResultClicked() = ServiceRoute(POST, "/internal/search/events/resultClicked")
    def commentIndexInfo() = ServiceRoute(GET, "/internal/search/comment/info")
    def commentReindex() = ServiceRoute(GET, "/internal/search/comment/reindex")
    def commentDumpLuceneDocument(id: Id[Comment]) = ServiceRoute(POST, "/internal/search/comment/dumpDoc", Param("id", id))
    def uriGraphInfo() = ServiceRoute(GET, "/internal/search/uriGraph/info")
    def sharingUserInfo(userId: Id[User], uriIds: String) = ServiceRoute(GET, "/internal/search/uriGraph/sharingUserInfo", Param("userId", userId), Param("uriIds", uriIds))
    def updateURIGraph() = ServiceRoute(POST, "/internal/search/uriGraph/update")
    def uriGraphReindex() = ServiceRoute(POST, "/internal/search/uriGraph/reindex")
    def uriGraphDumpLuceneDocument(id: Id[User]) = ServiceRoute(POST, s"/internal/search/uriGraph/dumpDoc/${id.id}")
    def collectionReindex() = ServiceRoute(POST, "/internal/search/collection/reindex")
    def collectionDumpLuceneDocument(id: Id[Collection], userId: Id[User]) = ServiceRoute(POST, "/internal/search/collection/dumpDoc", Param("id", id), Param("userId", userId))
    def indexInfo() = ServiceRoute(GET, "/internal/search/index/info")
    def searchUpdate() = ServiceRoute(POST, "/internal/search/index/update")
    def searchReindex() = ServiceRoute(POST, "/internal/search/index/reindex")
    def getSequenceNumber() = ServiceRoute(GET, "/internal/search/index/sequenceNumber")
    def refreshSearcher() = ServiceRoute(POST, "/internal/search/index/refreshSearcher")
    def refreshPhrases() = ServiceRoute(POST, "/internal/search/index/refreshPhrases")
    def searchDumpLuceneDocument(id: Id[NormalizedURI]) = ServiceRoute(POST, s"/internal/search/index/dumpDoc/${id.id}")
    def searchKeeps(userId: Id[User], query: String) = ServiceRoute(POST, "/internal/search/search/keeps", Param("userId", userId), Param("query", query))
    def explain(query: String, userId: Id[User], uriId: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/search/search/explainResult", Param("query", query), Param("userId", userId), Param("uriId", uriId))
    def causeError() = ServiceRoute(GET, "/internal/search/search/causeError")
    def buildDictionary() = ServiceRoute(POST, "/internal/search/spell/buildDict")
    def getBuildStatus() = ServiceRoute(GET, "/internal/search/spell/buildStatus")
    def correctSpelling(query: String) = ServiceRoute(GET, "/internal/search/spell/make-correction", Param("query", query))
    def getSearchStatistics() = ServiceRoute(POST, "/internal/search/getSearchStatistics")
    def showUserConfig(id: Id[User]) = ServiceRoute(GET, s"/internal/search/searchConfig/${id.id}")
    def setUserConfig(id: Id[User]) = ServiceRoute(POST, s"/internal/search/searchConfig/${id.id}/set")
    def resetUserConfig(id: Id[User]) = ServiceRoute(GET, s"/internal/search/searchConfig/${id.id}/reset")
    def getSearchDefaultConfig = ServiceRoute(GET, "/internal/search/defaultSearchConfig/defaultSearchConfig")
    def friendMapJson(userId: Id[User], query: Option[String] = None, minKeeps: Option[Int] = None) = ServiceRoute(GET, "/internal/search/search/friendMapJson", Param("userId", userId), Param("query", query), Param("minKeeps", minKeeps))
  }
}

object Common {
  object internal {
    def benchmarksResults() = ServiceRoute(GET, "/internal/benchmark")
    def version() = ServiceRoute(GET, "/internal/version")
  }
}

