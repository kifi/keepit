package com.keepit.graph.wander

import com.google.inject.Inject
import com.keepit.graph.manager.GraphManager
import com.keepit.common.time.Clock
import com.keepit.common.logging.Logging
import com.keepit.graph.model._
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.model.{ SocialUserInfo, User }
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.collection.mutable.ListBuffer
import com.keepit.abook.model.EmailAccountInfo
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class SocialWanderingCommander @Inject() (
    graph: GraphManager,
    relatedUsersCache: SociallyRelatedUsersCache,
    relatedFacebookAccountsCache: SociallyRelatedFacebookAccountsCache,
    relatedLinkedInAccountsCache: SociallyRelatedLinkedInAccountsCache,
    relatedEmailAccountsCache: SociallyRelatedEmailAccountsCache,
    clock: Clock) extends Logging {

  private val consolidate = new RequestConsolidator[Id[User], Unit](1 minute)
  private val lock = new ReactiveLock(5)

  def refresh(id: Id[User]): Future[Unit] = consolidate(id) { userId =>
    lock.withLockFuture {
      getIrrelevantVertices(userId).map { irrelevantVertices =>
        val journal = wander(userId, irrelevantVertices)
        invalidateCache(userId, journal)
      }
    }
  }

  private def invalidateCache(userId: Id[User], journal: TeleportationJournal): Unit = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    val relatedUsers = ListBuffer[(Id[User], Double)]()
    val relatedFacebookAccounts = ListBuffer[(Id[SocialUserInfo], Double)]()
    val relatedLinkedInAccounts = ListBuffer[(Id[SocialUserInfo], Double)]()
    val relatedEmailAccounts = ListBuffer[(Id[EmailAccountInfo], Double)]()

    @inline def normalizedScore(score: Int) = score.toDouble / journal.getCompletedSteps()

    journal.getVisited().foreach {
      case (id, score) if id.kind == UserReader =>
        val userId = VertexDataId.toUserId(id.asId[UserReader])
        relatedUsers += userId -> normalizedScore(score)
      case (id, score) if id.kind == FacebookAccountReader =>
        val socialUserId = VertexDataId.fromFacebookAccountIdtoSocialUserId(id.asId[FacebookAccountReader])
        relatedFacebookAccounts += socialUserId -> normalizedScore(score)
      case (id, score) if id.kind == LinkedInAccountReader =>
        val socialUserId = VertexDataId.fromLinkedInAccountIdtoSocialUserId(id.asId[LinkedInAccountReader])
        relatedLinkedInAccounts += socialUserId -> normalizedScore(score)
      case (id, score) if id.kind == EmailAccountReader =>
        val emailAccountId = VertexDataId.toEmailAccountId(id.asId[EmailAccountReader])
        relatedEmailAccounts += emailAccountId -> normalizedScore(score)
      case _ => // ignore
    }

    relatedUsersCache.set(SociallyRelatedUsersCacheKey(userId), RelatedEntities.top(userId, relatedUsers, SocialWanderlust.topCached))
    relatedFacebookAccountsCache.set(SociallyRelatedFacebookAccountsCacheKey(userId), RelatedEntities.top(userId, relatedFacebookAccounts, SocialWanderlust.topCached))
    relatedLinkedInAccountsCache.set(SociallyRelatedLinkedInAccountsCacheKey(userId), RelatedEntities.top(userId, relatedLinkedInAccounts, SocialWanderlust.topCached))
    relatedEmailAccountsCache.set(SociallyRelatedEmailAccountsCacheKey(userId), RelatedEntities.top(userId, relatedEmailAccounts, SocialWanderlust.topCached))
  }

  private def wander(userId: Id[User], irrelevantVertices: Set[VertexId]): TeleportationJournal = {
    val userVertexId = VertexId(userId)
    val journal = new TeleportationJournal(clock)
    val teleporter = UniformTeleporter(Set(userVertexId)) { Function.const(SocialWanderlust.restartProbability) }
    val resolver = {
      val mayTraverse: (VertexReader, VertexReader, EdgeReader) => Boolean = {
        case (source, destination, edge) => !(irrelevantVertices.contains(destination.id) || journal.getLastVisited().exists(_ == destination.id))
      }
      RestrictedDestinationResolver(Some(SocialWanderlust.subgraph), mayTraverse, Function.const(1))
    }

    graph.readOnly { reader =>
      val wanderer = reader.getNewVertexReader()
      val scout = reader.getNewVertexReader()
      val scoutingWanderer = new ScoutingWanderer(wanderer, scout)
      scoutingWanderer.wander(SocialWanderlust.steps, teleporter, resolver, journal)
    }
    journal
  }

  private def getIrrelevantVertices(userId: Id[User]): Future[Set[VertexId]] = Future.successful(Set.empty)
}

object SocialWanderlust {

  val restartProbability = 0.15
  val steps = 100000
  val topCached = 2000

  val subgraph = Set(
    // Kifi Social Graph
    Component(UserReader, UserReader, EmptyEdgeReader),

    // Facebook Graph
    Component(UserReader, FacebookAccountReader, EmptyEdgeReader),
    Component(FacebookAccountReader, UserReader, EmptyEdgeReader),
    Component(FacebookAccountReader, FacebookAccountReader, EmptyEdgeReader),

    // LinkedIn Graph
    Component(UserReader, LinkedInAccountReader, EmptyEdgeReader),
    Component(LinkedInAccountReader, UserReader, EmptyEdgeReader),
    Component(LinkedInAccountReader, LinkedInAccountReader, EmptyEdgeReader),

    // Email Graph
    Component(UserReader, EmailAccountReader, EmptyEdgeReader),
    Component(EmailAccountReader, UserReader, EmptyEdgeReader),
    Component(UserReader, AddressBookReader, EmptyEdgeReader),
    Component(AddressBookReader, UserReader, EmptyEdgeReader),
    Component(EmailAccountReader, AddressBookReader, EmptyEdgeReader),
    Component(AddressBookReader, EmailAccountReader, EmptyEdgeReader)
  )
}
