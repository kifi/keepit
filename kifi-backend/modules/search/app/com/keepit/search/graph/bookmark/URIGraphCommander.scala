package com.keepit.search.graph.bookmark

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import com.keepit.search.graph.URIList
import com.keepit.search.sharding.Shard
import com.keepit.search.{ SharingUserInfo, MainSearcherFactory }

case class UserURIList(publicList: Option[URIList], privateList: Option[URIList])
case class RequestingUser(userId: Id[User])

@Singleton
class URIGraphCommanderFactory @Inject() (mainSearcherFactory: MainSearcherFactory) {
  def apply(userId: Id[User]): URIGraphCommander = {
    new URIGraphCommanderImpl(RequestingUser(userId), mainSearcherFactory)
  }
}

trait URIGraphCommander {
  val requestingUser: RequestingUser
  def getUserUriList(userId: Id[User], publicOnly: Boolean, shard: Shard[NormalizedURI]): UserURIList
  def getUserUriLists(userIds: Set[Id[User]], publicOnly: Boolean, shard: Shard[NormalizedURI]): Map[Id[User], UserURIList]
  def getSharingUserInfo(uriId: Id[NormalizedURI], shard: Shard[NormalizedURI]): SharingUserInfo
}

class URIGraphCommanderImpl(
    val requestingUser: RequestingUser,
    val mainSearcherFactory: MainSearcherFactory) extends URIGraphCommander {

  private def getURIGraphSearcher(shard: Shard[NormalizedURI]) = mainSearcherFactory.getURIGraphSearcher(shard, requestingUser.userId)

  private def getURIList(userId: Id[User], publicOnly: Boolean, searcher: URIGraphSearcher): UserURIList = {
    val edgeSet = searcher.getUserToUriEdgeSet(userId, publicOnly)
    UserURIList(edgeSet.getPublicList, edgeSet.getPrivateList)
  }

  def getUserUriList(userId: Id[User], publicOnly: Boolean, shard: Shard[NormalizedURI]): UserURIList = {
    getURIList(userId, publicOnly, getURIGraphSearcher(shard))
  }

  def getUserUriLists(userIds: Set[Id[User]], publicOnly: Boolean, shard: Shard[NormalizedURI]): Map[Id[User], UserURIList] = {
    val searcher = getURIGraphSearcher(shard)
    userIds.foldLeft(Map.empty[Id[User], UserURIList]) { case (m, userId) => m + (userId -> getURIList(userId, publicOnly, searcher)) }
  }

  def getSharingUserInfo(uriId: Id[NormalizedURI], shard: Shard[NormalizedURI]): SharingUserInfo = {
    val searcher = getURIGraphSearcher(shard)
    searcher.getSharingUserInfo(uriId)
  }
}
