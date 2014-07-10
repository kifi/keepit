package com.keepit.search.feed

import org.joda.time.DateTime
import com.google.inject.Inject
import com.google.inject.ImplementedBy
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import com.keepit.search.graph.URIList
import com.keepit.search.graph.user.UserGraphsSearcherFactory
import com.keepit.search.graph.Util
import com.keepit.search.graph.bookmark.URIGraphCommanderFactory
import com.keepit.search.sharding.{ Sharding, ActiveShards, Shard }
import com.keepit.search.SearchServiceClient
import com.keepit.shoebox.ShoeboxServiceClient
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ Promise, Future }
import scala.concurrent.duration.DurationInt
import scala.util.Try
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@ImplementedBy(classOf[FeedCommanderImpl])
trait FeedCommander {
  def getFeeds(userId: Id[User], limit: Int): Seq[Feed]
  def distFeeds(shard: Set[Shard[NormalizedURI]], userId: Id[User], limit: Int): Seq[Feed]
}

class FeedCommanderImpl @Inject() (
    shards: ActiveShards,
    userGraphsSearcherFactory: UserGraphsSearcherFactory,
    uriGraphCommanderFactory: URIGraphCommanderFactory,
    override val searchClient: SearchServiceClient,
    shoeboxClient: ShoeboxServiceClient,
    feedMetaInfoProvider: FeedMetaInfoProvider,
    monitoredAwait: MonitoredAwait) extends FeedCommander with Sharding with Logging {

  val feedFilter = new CompositeFeedFilter(new BasicFeedFilter())

  private def aggregate(uriLists: Seq[URIList], myKeeps: Set[Long]): Seq[(Long, Long)] = {
    // for same uri, take oldest kept time
    val idAndTime = uriLists.map { x => (x.ids zip x.createdAt) }.flatten
    idAndTime.groupBy(_._1).toSeq.filterNot { x => myKeeps.contains(x._1) }.map { x => x._2.min }
  }

  private def buildFeed(uri: NormalizedURI, firstKeptAt: Long, sharingUsers: Set[Id[User]], keepersEdgeSetSize: Int): Feed = {
    val users = shoeboxClient.getBasicUsers(sharingUsers.toSeq)
    Feed(uri, monitoredAwait.result(users, 1 seconds, "getting basic users").values.toSeq, new DateTime(Util.unitToMillis(firstKeptAt), DEFAULT_DATE_TIME_ZONE), keepersEdgeSetSize)
  }

  def getFeeds(userId: Id[User], limit: Int): Seq[Feed] = {

    val (localShards, dispatchPlan) = distributionPlan(shards)

    var resultFutures = new ListBuffer[Future[Seq[Feed]]]()

    if (dispatchPlan.nonEmpty) {
      searchClient.distFeeds(dispatchPlan, userId, limit).foreach { f =>
        resultFutures += f
      }
    }
    if (localShards.nonEmpty) {
      resultFutures += Promise[Seq[Feed]].complete(
        Try { distFeeds(localShards, userId, limit) }
      ).future
    }

    val results = monitoredAwait.result(Future.sequence(resultFutures), 10 seconds, "getting feeds").flatten

    results.sortBy(x => -x.firstKeptAt.getMillis).take(limit)
  }

  def distFeeds(shards: Set[Shard[NormalizedURI]], userId: Id[User], limit: Int): Seq[Feed] = {
    val uriGraphCommander = uriGraphCommanderFactory(userId)
    val userGraphsSearcher = userGraphsSearcherFactory(userId)

    val friends = userGraphsSearcher.getSearchFriends()
    val uriList = shards.toSeq.map { shard =>
      val myUriList = uriGraphCommander.getUserUriList(userId, publicOnly = false, shard = shard)
      val myKeeps = (myUriList.publicList.getOrElse(URIList.empty).ids ++ myUriList.privateList.getOrElse(URIList.empty).ids).toSet

      val uriLists = friends.map { id =>
        uriGraphCommander.getUserUriList(Id[User](id), publicOnly = true, shard)
      }
      aggregate(uriLists.flatMap { _.publicList }.toSeq, myKeeps)
    }.flatten

    val urisSorted = uriList.sortBy(-_._2)

    var (i, cnt) = (0, 0)
    val feeds = new Array[Feed](limit)

    while (i < urisSorted.size && cnt < limit) {
      val (id, time) = urisSorted(i)
      val uid = Id[NormalizedURI](id)
      val meta = feedMetaInfoProvider.getFeedMetaInfo(uid)
      if (feedFilter.accept(meta)) {
        val sharingUserInfo = shards.find(_.contains(uid)).map { shard =>
          uriGraphCommander.getSharingUserInfo(uid, shard)
        }
        feeds(cnt) = buildFeed(meta.uri, time, sharingUserInfo.get.sharingUserIds, sharingUserInfo.get.keepersEdgeSetSize)
        cnt += 1
      }
      i += 1
    }
    feeds.take(cnt)
  }

  def getFeeds(userId: Id[User], pageNum: Int, pageSize: Int): Seq[Feed] = {
    getFeeds(userId, (pageNum + 1) * pageSize).drop(pageNum * pageSize).take(pageSize)
  }
}
