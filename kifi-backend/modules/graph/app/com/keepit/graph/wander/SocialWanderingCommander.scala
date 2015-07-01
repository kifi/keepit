package com.keepit.graph.wander

import com.google.inject.Inject
import com.keepit.graph.manager.GraphManager
import com.keepit.common.time.Clock
import com.keepit.common.logging.Logging
import com.keepit.graph.model._
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.model.{ Organization, SocialUserInfo, User }
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.collection.mutable.ListBuffer
import com.keepit.abook.model.EmailAccountInfo
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.abook.ABookServiceClient

class SocialWanderingCommander @Inject() (
    graph: GraphManager,
    relatedEntitiesCache: SociallyRelatedEntitiesForUserCache,
    abook: ABookServiceClient,
    clock: Clock) extends Logging {

  private val consolidateUser = new RequestConsolidator[Id[User], Option[SociallyRelatedEntities[User]]](1 minute)
  private val consolidateOrg = new RequestConsolidator[Id[Organization], Option[SociallyRelatedEntities[Organization]]](1 minute)
  private val lock = new ReactiveLock(5)

  def getSocialRelatedEntities(userId: Id[User]): Future[Option[SociallyRelatedEntities[User]]] = {
    getSocialRelatedEntitiesForUser(userId)
  }

  def getSocialRelatedEntitiesForUser(userId: Id[User]): Future[Option[SociallyRelatedEntities[User]]] = {
    consolidateUser(userId) { userId => getSocialRelatedEntities[User](userId)(getIrrelevantVerticesForUser) }
  }

  private def getSocialRelatedEntities[E](sourceId: Id[E])(getIrrelevantVertices: Id[E] => Future[Set[VertexId]]): Future[Option[SociallyRelatedEntities[E]]] = {
    val vertexId = VertexId(sourceId.id)
    lock.withLockFuture {
      if (graphHasSourceVertex(vertexId)) {
        getIrrelevantVertices(sourceId).map { irrelevantVertices =>
          val journal = wander(vertexId, irrelevantVertices)
          val sociallyRelatedPeople = buildSociallyRelatedPeople(sourceId, journal, SocialWanderlust.cachedByNetwork)
          Some(sociallyRelatedPeople)
        }
      } else {
        Future.successful(None)
      }
    }
  }

  private def graphHasSourceVertex(vertexId: VertexId): Boolean = {
    graph.readOnly { reader => reader.getNewVertexReader().hasVertex(vertexId) }
  }

  private def buildSociallyRelatedPeople[E](sourceId: Id[E], journal: TeleportationJournal, limit: Int) = {
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

    SociallyRelatedEntities[E](
      users = RelatedEntities.top(sourceId, relatedUsers, limit),
      facebookAccounts = RelatedEntities.top(sourceId, relatedFacebookAccounts, limit),
      linkedInAccounts = RelatedEntities.top(sourceId, relatedLinkedInAccounts, limit),
      emailAccounts = RelatedEntities.top(sourceId, relatedEmailAccounts, limit)
    )
  }

  private def invalidateUserCache(sociallyRelatedPeople: SociallyRelatedEntities[User]): Unit = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    relatedEntitiesCache.set(SociallyRelatedEntitiesForUserCacheKey(sociallyRelatedPeople.users.id), sociallyRelatedPeople)
  }

  private def wander(vertexId: VertexId, irrelevantVertices: Set[VertexId]): TeleportationJournal = {
    val journal = new TeleportationJournal("SocialWanderingJournal", clock)
    val teleporter = UniformTeleporter(Set(vertexId)) { Function.const(SocialWanderlust.restartProbability) }
    val resolver = {
      val mayTraverse: (VertexReader, VertexReader, EdgeReader) => Boolean = {
        case (source, destination, edge) => !(irrelevantVertices.contains(destination.id))
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

  private def getIrrelevantVerticesForUser(sourceId: Id[User]): Future[Set[VertexId]] = {
    abook.getIrrelevantPeopleForUser(sourceId).map { irrelevantPeople =>
      irrelevantPeople.irrelevantUsers.map(VertexId(_)) ++ SocialWanderlust.explicitlyExcludedUsers ++
        irrelevantPeople.irrelevantFacebookAccounts.map(id => VertexId(VertexDataId.fromSocialUserIdToFacebookAccountId(id))) ++
        irrelevantPeople.irrelevantLinkedInAccounts.map(id => VertexId(VertexDataId.fromSocialUserIdToLinkedInAccountId(id))) ++
        irrelevantPeople.irrelevantEmailAccounts.map(VertexId(_))
    }
  }
}

object SocialWanderlust {

  val restartProbability = 0.15
  val steps = 100000
  val cachedByNetwork = 2000

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
    Component(AddressBookReader, EmailAccountReader, EmptyEdgeReader),
    Component(EmailAccountReader, DomainReader, EmptyEdgeReader),
    Component(DomainReader, EmailAccountReader, EmptyEdgeReader),

    // Organizations Graph
    Component(UserReader, OrganizationReader, TimestampEdgeReader),
    Component(OrganizationReader, UserReader, TimestampEdgeReader),

    // Ip Address Graph
    Component(UserReader, IpAddressReader, TimestampEdgeReader),
    Component(IpAddressReader, UserReader, TimestampEdgeReader)
  )

  val explicitlyExcludedUsers = Seq(
    10015, // Kifi Editorial
    11784, // Kifi Product
    11785, // Kifi Eng
    16707 // Kifi Twitter
  ).map(id => VertexId(Id[User](id)))
}
