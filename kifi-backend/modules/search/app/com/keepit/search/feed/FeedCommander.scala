package com.keepit.search.feed

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import org.joda.time.DateTime
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import com.keepit.search.graph.URIList
import com.keepit.search.graph.bookmark.URIGraphCommander
import com.keepit.search.graph.bookmark.URIGraphSearcher
import com.keepit.search.graph.user.UserGraphsSearcherFactory
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.graph.Util
import com.keepit.search.graph.bookmark.URIGraphCommanderFactory


class FeedCommanderFactory @Inject()(
  uriGraphCommanderFactory: URIGraphCommanderFactory,
  userGraphsSearcherFactory: UserGraphsSearcherFactory,
  shoeboxClient: ShoeboxServiceClient,
  feedMetaInfoProvider: FeedMetaInfoProvider
){
  def apply(userId: Id[User]): FeedCommander = {
    val ugCmdr = uriGraphCommanderFactory(userId)
    new FeedCommanderImpl(userGraphsSearcherFactory, ugCmdr, shoeboxClient, feedMetaInfoProvider)
  }
}


trait FeedCommander {
  def getFeeds(userId: Id[User], limit: Int): Seq[Feed]
  def getFeeds(userId: Id[User], pageNum: Int, pageSize: Int): Seq[Feed]
}

class FeedCommanderImpl(
  userGraphsSearcherFactory: UserGraphsSearcherFactory,
  uriGraphCommander: URIGraphCommander,
  shoeboxClient: ShoeboxServiceClient,
  feedMetaInfoProvider: FeedMetaInfoProvider
) extends FeedCommander {
  val feedFilter = new CompositeFeedFilter(new BasicFeedFilter())

  private def aggregateAndSort(uriLists: Seq[URIList]): Seq[(Long, Long)] = {
    // for same uri, take oldest kept time
    val idAndTime = uriLists.map{ x => (x.ids zip x.createdAt) }.flatten
    idAndTime.groupBy(_._1).map{ x => x._2.min}.toSeq.sortBy(-_._2)
  }

  private def buildFeed(uri: NormalizedURI, firstKeptAt: Long, sharingUsers: Set[Id[User]], keepersEdgeSetSize: Int): Feed = {
    val users = shoeboxClient.getBasicUsers(sharingUsers.toSeq)
    Feed(uri, Await.result(users, 1 seconds).values.toSeq, new DateTime(Util.unitToMillis(firstKeptAt), DEFAULT_DATE_TIME_ZONE), keepersEdgeSetSize)
  }

  def getFeeds(userId: Id[User], limit: Int): Seq[Feed] = {
    val mykeeps = uriGraphCommander.getUserUriList(userId, publicOnly = false).values.map{ uriList =>
      uriList.publicList.getOrElse(URIList.empty).ids ++ uriList.privateList.getOrElse(URIList.empty).ids
    }.flatten.toSet

    val userGraphsSearcher = userGraphsSearcherFactory(userId)
    val friends = userGraphsSearcher.getSearchFriends()
    val shards = uriGraphCommander.getIndexShards
    val uriGraphSearchers = shards.map{ shard => (shard, URIGraphSearcher(userId, uriGraphCommander.getIndexer(shard), userGraphsSearcher))}.toMap
    val uriLists = shards.map{ shard =>
      val uriLists = friends.map{ id =>
        uriGraphCommander.getUserUriList(Id[User](id), publicOnly = true, shard)
      }
      aggregateAndSort(uriLists.flatMap{_.publicList}.toSeq).take(limit)
    }.flatten

    val urisSorted = uriLists.sortBy(-_._2).toArray

    var (i, cnt) = (0, 0)
    val feeds = new Array[Feed](limit)

    while(i < urisSorted.size && cnt < limit){
      val (id, time) = urisSorted(i)
      val uid = Id[NormalizedURI](id)
      val meta = feedMetaInfoProvider.getFeedMetaInfo(uid)
      if (feedFilter.accept(meta) && !mykeeps.contains(id)){
        val sharingUserInfo = shards.find(_.contains(uid)).map{ shard =>
          uriGraphSearchers(shard).getSharingUserInfo(uid)
        }
        feeds(cnt) = buildFeed(meta.uri, time, sharingUserInfo.get.sharingUserIds, sharingUserInfo.get.keepersEdgeSetSize)
        cnt += 1
      }
      i += 1
    }
    feeds.take(cnt)
  }

  def getFeeds(userId: Id[User], pageNum: Int, pageSize: Int): Seq[Feed] = {
    getFeeds(userId, (pageNum + 1)*pageSize).drop(pageNum * pageSize).take(pageSize)
  }
}
