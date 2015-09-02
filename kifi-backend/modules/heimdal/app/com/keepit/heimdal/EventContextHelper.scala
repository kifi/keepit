package com.keepit.heimdal

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commander.HelpRankCommander
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.model.helprank.KeepDiscoveryRepo
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.cache._
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@ImplementedBy(classOf[EventContextHelperImpl])
trait EventContextHelper {
  def getPrimaryOrg(userId: Id[User]): Future[Option[Id[Organization]]]
  def getOrgTrackingValues(orgId: Id[Organization]): Future[OrgTrackingValues]
  def getOrgUserValues(orgId: Id[Organization]): Future[Seq[(String, ContextData)]]
}

class EventContextHelperImpl @Inject() (
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    orgTrackingValuesCache: OrgTrackingValuesCache,
    shoebox: ShoeboxServiceClient,
    eliza: ElizaServiceClient,
    helprankCommander: HelpRankCommander,
    db: Database) extends EventContextHelper {
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
}
