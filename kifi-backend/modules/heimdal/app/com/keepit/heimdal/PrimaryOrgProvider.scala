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

@ImplementedBy(classOf[PrimaryOrgProviderImpl])
trait PrimaryOrgProvider {
  def getPrimaryOrg(userId: Id[User]): Future[Option[Id[Organization]]]
  def getOrgTrackingValues(orgId: Id[Organization]): Future[OrgTrackingValues]
  def getOrgContextData(orgId: Id[Organization]): Future[Map[String, ContextData]]
}

class PrimaryOrgProviderImpl @Inject() (
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    orgTrackingValuesCache: OrgTrackingValuesCache,
    shoebox: ShoeboxServiceClient,
    eliza: ElizaServiceClient,
    helprankCommander: HelpRankCommander,
    db: Database) extends PrimaryOrgProvider {
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

  def getOrgContextData(orgId: Id[Organization]): Future[Map[String, ContextData]] = {
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
      Map(
        "orgId" -> ContextStringData(orgId.toString),
        "libraryCount" -> ContextDoubleData(shoeboxValues.libraryCount),
        "keepCount" -> ContextDoubleData(shoeboxValues.keepCount),
        "inviteCount" -> ContextDoubleData(shoeboxValues.inviteCount),
        "collabLibCount" -> ContextDoubleData(shoeboxValues.collabLibCount),
        "messageCount" -> ContextDoubleData(messageCount)
      ) ++ userWithMostClickedKeeps.map(userId => Map("overallKeepViews" -> ContextStringData(userId.toString))).getOrElse(Map.empty)
    }
  }
}
