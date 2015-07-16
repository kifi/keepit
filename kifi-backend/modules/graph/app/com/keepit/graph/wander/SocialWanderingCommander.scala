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
    userRelatedEntitiesCache: SociallyRelatedEntitiesForUserCache,
    orgRelatedEntitiesCache: SociallyRelatedEntitiesForOrgCache,
    abook: ABookServiceClient,
    clock: Clock) extends Logging {

  private val consolidateUser = new RequestConsolidator[Id[User], Option[SociallyRelatedEntitiesForUser]](1 minute)
  private val consolidateOrg = new RequestConsolidator[Id[Organization], Option[SociallyRelatedEntitiesForOrg]](1 minute)
  private val lock = new ReactiveLock(5)

  def getSociallyRelatedEntitiesForUser(userId: Id[User]): Future[Option[SociallyRelatedEntitiesForUser]] = consolidateUser(userId) { userId =>
    val vertexId = VertexId(UserReader)(userId.id)
    lock.withLockFuture {
      if (graphHasSourceVertex(vertexId)) {
        getIrrelevantVerticesForUser(userId).map { irrelevantVertices =>
          val journal = wander(vertexId, irrelevantVertices)
          val sociallyRelatedPeople = buildSociallyRelatedPeopleForUser(userId, journal, SocialWanderlust.cachedByNetwork)
          Some(sociallyRelatedPeople)
        }
      } else {
        Future.successful(None)
      }
    }
  }

  def getSociallyRelatedEntitiesForOrg(orgId: Id[Organization]): Future[Option[SociallyRelatedEntitiesForOrg]] = consolidateOrg(orgId) { orgId =>
    val vertexId = VertexId(OrganizationReader)(orgId.id)
    lock.withLockFuture {
      if (graphHasSourceVertex(vertexId)) {
        getIrrelevantVerticesForOrg(orgId).map { irrelevantVertices =>
          val journal = wander(vertexId, irrelevantVertices)
          val sociallyRelatedPeople = buildSociallyRelatedPeopleForOrg(orgId, journal, SocialWanderlust.cachedByNetwork)
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

  private def buildSociallyRelatedPeopleForUser(userId: Id[User], journal: TeleportationJournal, limit: Int) = {
    val relatedUsers = ListBuffer[(Id[User], Double)]()
    val relatedFacebookAccounts = ListBuffer[(Id[SocialUserInfo], Double)]()
    val relatedLinkedInAccounts = ListBuffer[(Id[SocialUserInfo], Double)]()
    val relatedEmailAccounts = ListBuffer[(Id[EmailAccountInfo], Double)]()
    val relatedOrganizations = ListBuffer[(Id[Organization], Double)]()

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
      case (id, score) if id.kind == OrganizationReader =>
        val organizationId = VertexDataId.toOrganizationId(id.asId[OrganizationReader])
        relatedOrganizations += organizationId -> normalizedScore(score)
      case _ => // ignore
    }

    SociallyRelatedEntitiesForUser(
      users = RelatedEntities.top(userId, relatedUsers, limit),
      facebookAccounts = RelatedEntities.top(userId, relatedFacebookAccounts, limit),
      linkedInAccounts = RelatedEntities.top(userId, relatedLinkedInAccounts, limit),
      emailAccounts = RelatedEntities.top(userId, relatedEmailAccounts, limit),
      organizations = RelatedEntities.top(userId, relatedOrganizations, limit)
    )
  }

  private def buildSociallyRelatedPeopleForOrg(orgId: Id[Organization], journal: TeleportationJournal, limit: Int) = {
    val relatedUsers = ListBuffer[(Id[User], Double)]()
    val relatedEmailAccounts = ListBuffer[(Id[EmailAccountInfo], Double)]()

    @inline def normalizedScore(score: Int) = score.toDouble / journal.getCompletedSteps()

    journal.getVisited().foreach {
      case (id, score) if id.kind == UserReader =>
        val userId = VertexDataId.toUserId(id.asId[UserReader])
        relatedUsers += userId -> normalizedScore(score)
      case (id, score) if id.kind == EmailAccountReader =>
        val emailAccountId = VertexDataId.toEmailAccountId(id.asId[EmailAccountReader])
        relatedEmailAccounts += emailAccountId -> normalizedScore(score)
      case _ => // ignore
    }

    SociallyRelatedEntitiesForOrg(
      users = RelatedEntities.top(orgId, relatedUsers, limit),
      emailAccounts = RelatedEntities.top(orgId, relatedEmailAccounts, limit)
    )
  }

  private def invalidateUserCache(sociallyRelatedPeople: SociallyRelatedEntitiesForUser): Unit = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    userRelatedEntitiesCache.set(SociallyRelatedEntitiesForUserCacheKey(sociallyRelatedPeople.users.id), sociallyRelatedPeople)
  }

  private def invalidateOrgCache(sociallyRelatedPeople: SociallyRelatedEntitiesForOrg): Unit = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    orgRelatedEntitiesCache.set(SociallyRelatedEntitiesForOrgCacheKey(sociallyRelatedPeople.users.id), sociallyRelatedPeople)
  }

  private def wander(vertexId: VertexId, irrelevantVertices: Set[VertexId]): TeleportationJournal = {
    val journal = new TeleportationJournal("SocialWanderingJournal", clock)
    val teleporter = UniformTeleporter(Set(vertexId)) { Function.const(SocialWanderlust.restartProbability) }
    val resolver = {
      val mayTraverse: (VertexReader, VertexReader, EdgeReader) => Boolean = {
        case (source, destination, edge) => !irrelevantVertices.contains(destination.id)
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

  private def getIrrelevantVerticesForUser(userId: Id[User]): Future[Set[VertexId]] = {
    abook.getIrrelevantPeopleForUser(userId).map { irrelevantPeople =>
      irrelevantPeople.irrelevantUsers.map(VertexId(_)) ++ SocialWanderlust.explicitlyExcludedUsers ++
        irrelevantPeople.irrelevantFacebookAccounts.map(id => VertexId(VertexDataId.fromSocialUserIdToFacebookAccountId(id))) ++
        irrelevantPeople.irrelevantLinkedInAccounts.map(id => VertexId(VertexDataId.fromSocialUserIdToLinkedInAccountId(id))) ++
        irrelevantPeople.irrelevantEmailAccounts.map(VertexId(_))
    }
  }

  private def getIrrelevantVerticesForOrg(orgId: Id[Organization]): Future[Set[VertexId]] = {
    abook.getIrrelevantPeopleForOrg(orgId).map { irrelevantPeople =>
      irrelevantPeople.irrelevantUsers.map(VertexId(_)) ++ SocialWanderlust.explicitlyExcludedUsers ++
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
    16707, // Kifi Twitter
    97543 // Fake Org Owner
  ).map(id => VertexId(Id[User](id)))
}
