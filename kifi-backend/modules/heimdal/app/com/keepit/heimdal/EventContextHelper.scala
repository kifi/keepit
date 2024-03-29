package com.keepit.heimdal

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commander.HelpRankCommander
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.AccessLog
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.model.helprank.KeepDiscoveryRepo
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.cache._
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Random

@ImplementedBy(classOf[EventContextHelperImpl])
trait EventContextHelper {
  def getPrimaryOrg(userId: Id[User]): Future[Option[Id[Organization]]]
  def getOrgSpecificValues(orgId: Id[Organization]): Future[Seq[(String, ContextData)]]
  def getOrgUserValues(orgId: Id[Organization], userId: Id[User]): Future[Seq[(String, ContextData)]]
  def getLibraryEventValues(libraryId: Id[Library], userId: Id[User]): Future[Seq[(String, ContextData)]]
  def getOrganizationIdByExtThreadId(threadExtId: String): Future[Option[Id[Organization]]]
}

class EventContextHelperImpl @Inject() (
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    orgTrackingValuesCache: OrgTrackingValuesCache,
    orgMessageCountCache: OrganizationMessageCountCache,
    orgMemberWithMostClickedKeepsCache: OrgMemberWithMostClickedKeepsCache,
    shoebox: ShoeboxServiceClient,
    eliza: ElizaServiceClient,
    helprankCommander: HelpRankCommander,
    db: Database) extends EventContextHelper {

  val FAKE_ORG_OWNER_ID = ExternalId[User]("4f738134-cc5a-4998-86ea-e5b5770731c9")

  def getPrimaryOrg(userId: Id[User]): Future[Option[Id[Organization]]] = {
    primaryOrgForUserCache.getOrElseFutureOpt(PrimaryOrgForUserKey(userId)) {
      shoebox.getPrimaryOrg(userId)
    }
  }

  private def getOrgTrackingValues(orgId: Id[Organization]): Future[OrgTrackingValues] = {
    orgTrackingValuesCache.getOrElseFuture(OrgTrackingValuesKey(orgId)) {
      shoebox.getOrgTrackingValues(orgId)
    }
  }

  def getOrgSpecificValues(orgId: Id[Organization]): Future[Seq[(String, ContextData)]] = {
    val shoeboxValuesFut = getOrgTrackingValues(orgId)

    val userWithMostClickedKeepsFut = orgMemberWithMostClickedKeepsCache.getOrElseFuture(OrgMemberWithMostClickedKeepsKey(orgId)) {
      val membersFut = shoebox.getOrganizationMembers(orgId)
      membersFut.map(helprankCommander.getUserWithMostClickedKeeps)
    }
    for {
      shoeboxValues <- shoeboxValuesFut
      userWithMostClickedKeeps <- userWithMostClickedKeepsFut
    } yield {
      Seq(
        ("userOrgId", ContextStringData(orgId.toString)),
        ("orgLibrariesCreated", ContextDoubleData(shoeboxValues.libraryCount)),
        ("orgKeepCount", ContextDoubleData(shoeboxValues.keepCount)),
        ("orgInviteCount", ContextDoubleData(shoeboxValues.inviteCount)),
        ("orgLibrariesCollaborating", ContextDoubleData(shoeboxValues.collabLibCount))
      ) ++ userWithMostClickedKeeps.map(userId => Seq(("overallKeepViews", ContextStringData(userId.id.toString)))).getOrElse(Seq.empty[(String, ContextData)])
    }
  }

  def getOrgUserValues(orgId: Id[Organization], userId: Id[User]): Future[Seq[(String, ContextData)]] = {

    val basicOrgFut = shoebox.getBasicOrganizationsByIds(Set(orgId)).map { orgsById => orgsById.values.head } // cached
    val orgUserRelationsFut = shoebox.getOrganizationUserRelationship(orgId, userId)

    for {
      basicOrg <- basicOrgFut
      orgUserRelations <- orgUserRelationsFut
    } yield {
      val orgStatus = basicOrg.ownerId match {
        case FAKE_ORG_OWNER_ID => ContextStringData("candidate")
        case _ => ContextStringData("real")
      }

      val memberStatusOpt = orgUserRelations match {
        case relations if relations.role.isDefined => Some(ContextStringData("fullMember"))
        case relations if relations.isInvited => Some(ContextStringData("pendingMember"))
        case relations if relations.isCandidate => Some(ContextStringData("candidateMember"))
        case _ => None
      }

      val orgRoleOpt = orgUserRelations.role.map(role => ContextStringData(role.value))

      Seq(("eventOrgId", ContextStringData(orgId.toString)), ("orgStatus", orgStatus)) ++ memberStatusOpt.map(status => Seq(("memberStatus", status))).getOrElse(Seq.empty[(String, ContextStringData)]) ++
        orgRoleOpt.map(role => Seq(("orgRole", role))).getOrElse(Seq.empty[(String, ContextStringData)])
    }
  }

  def getLibraryEventValues(libraryId: Id[Library], userId: Id[User]): Future[Seq[(String, ContextData)]] = {
    shoebox.getLibraryMembershipView(libraryId, userId).map { membershipOpt =>
      val libraryStatusOpt = membershipOpt.map { membership =>
        membership.access match {
          case LibraryAccess.READ_ONLY => ContextStringData("follower")
          case LibraryAccess.READ_WRITE => ContextStringData("collaborator")
          case LibraryAccess.OWNER => ContextStringData("owner")
        }
      }
      libraryStatusOpt.map(libraryStatus => Seq(("libraryStatus", libraryStatus))).getOrElse(Seq.empty)
    }
  }

  def getOrganizationIdByExtThreadId(threadExtId: String): Future[Option[Id[Organization]]] = {
    eliza.getParticipantsByThreadExtId(threadExtId).flatMap { userIds =>
      shoebox.getOrganizationsForUsers(userIds).map { orgsByUserId =>
        orgsByUserId.values.reduceLeftOption[Set[Id[Organization]]] {
          case (acc, orgSet) => acc.intersect(orgSet)
        }.flatMap {
          case commonOrgs if commonOrgs.nonEmpty => Some(Random.shuffle(commonOrgs).head) // track an arbitrary common org
          case _ => None
        }
      }
    }
  }
}

case class OrganizationMessageCountKey(id: Id[Organization]) extends Key[Int] {
  override val version = 1
  val namespace = "org_message_count"
  def toKey(): String = id.id.toString
}

class OrganizationMessageCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[OrganizationMessageCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class OrgMemberWithMostClickedKeepsKey(id: Id[Organization]) extends Key[Option[Id[User]]] {
  override val version = 1
  val namespace = "org_member_most_clicked_keeps"
  def toKey(): String = id.id.toString
}

class OrgMemberWithMostClickedKeepsCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[OrgMemberWithMostClickedKeepsKey, Option[Id[User]]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
