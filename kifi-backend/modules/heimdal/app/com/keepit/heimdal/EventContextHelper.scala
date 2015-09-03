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

@ImplementedBy(classOf[EventContextHelperImpl])
trait EventContextHelper {
  def getPrimaryOrg(userId: Id[User]): Future[Option[Id[Organization]]]
  def getOrgTrackingValues(orgId: Id[Organization]): Future[OrgTrackingValues]
  def getOrgUserValues(orgId: Id[Organization]): Future[Seq[(String, ContextData)]]
  def getOrgEventValues(orgId: Id[Organization], userId: Id[User]): Future[Seq[(String, ContextData)]]
  def getLibraryEventValues(libraryId: Id[Library], userId: Id[User]): Future[Seq[(String, ContextData)]]
}

class EventContextHelperImpl @Inject() (
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    orgTrackingValuesCache: OrgTrackingValuesCache,
    orgMessageCountCache: OrganizationMessageCountCache,
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

  def getOrgTrackingValues(orgId: Id[Organization]): Future[OrgTrackingValues] = {
    orgTrackingValuesCache.getOrElseFuture(OrgTrackingValuesKey(orgId)) {
      shoebox.getOrgTrackingValues(orgId)
    }
  }

  def getOrgUserValues(orgId: Id[Organization]): Future[Seq[(String, ContextData)]] = {
    val shoeboxValuesFut = getOrgTrackingValues(orgId)
    val membersFut = shoebox.getOrganizationMembers(orgId)
    val messageCountFut = membersFut.flatMap { members =>
      if (members.size > 1) eliza.getTotalMessageCountForGroup(members)
      else Future.successful(0)
    }
    val userWithMostClickedKeepsFut = membersFut.map(helprankCommander.getUserWithMostClickedKeeps)
    for {
      shoeboxValues <- shoeboxValuesFut
      members <- membersFut
      messageCount <- messageCountFut
      userWithMostClickedKeeps <- userWithMostClickedKeepsFut
    } yield {
      Seq(
        ("orgId", ContextStringData(orgId.toString)),
        ("orgLibrariesCreated", ContextDoubleData(shoeboxValues.libraryCount)),
        ("orgKeepCount", ContextDoubleData(shoeboxValues.keepCount)),
        ("orgInviteCount", ContextDoubleData(shoeboxValues.inviteCount)),
        ("orgLibrariesCollaborating", ContextDoubleData(shoeboxValues.collabLibCount)),
        ("orgMessageCount", ContextDoubleData(messageCount))
      ) ++ userWithMostClickedKeeps.map(userId => Seq(("overallKeepViews", ContextStringData(userId.toString)))).getOrElse(Seq.empty[(String, ContextData)])
    }
  }

  def getOrgEventValues(orgId: Id[Organization], userId: Id[User]): Future[Seq[(String, ContextData)]] = {

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

      Seq(("orgStatus", orgStatus)) ++ memberStatusOpt.map(status => Seq(("memberStatus", status))).getOrElse(Seq.empty[(String, ContextStringData)])
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
}

case class OrganizationMessageCountKey(id: Id[Organization]) extends Key[Int] {
  override val version = 1
  val namespace = "org_message_count"
  def toKey(): String = id.id.toString
}

class OrganizationMessageCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[OrganizationMessageCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
