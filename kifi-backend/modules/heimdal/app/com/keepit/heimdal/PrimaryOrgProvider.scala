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
  def getPrimaryOrgValues(orgId: Id[Organization]): Future[OrgTrackingValues]
}

class PrimaryOrgProviderImpl @Inject() (
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    primaryOrgValuesCache: OrgTrackingValuesCache,
    shoebox: ShoeboxServiceClient,
    eliza: ElizaServiceClient,
    helprankCommander: HelpRankCommander,
    db: Database) extends PrimaryOrgProvider {
  def getPrimaryOrg(userId: Id[User]): Future[Option[Id[Organization]]] = {
    primaryOrgForUserCache.getOrElseFutureOpt(PrimaryOrgForUserKey(userId)) {
      shoebox.getPrimaryOrg(userId)
    }
  }

  def getPrimaryOrgValues(orgId: Id[Organization]): Future[OrgTrackingValues] = {
    primaryOrgValuesCache.getOrElseFuture(OrgTrackingValuesKey(orgId)) {
      val shoeboxValuesFut = shoebox.getOrgTrackingValues(orgId)
      val orgMembersFut = shoebox.getOrganizationMembers(orgId)
      for {
        shoeboxValues <- shoeboxValuesFut
        members <- orgMembersFut
        messageCount <- eliza.getTotalMessageCountForGroup(members)
        popularKeeper = helprankCommander.getPopularKeeper(members)
      } yield {
        shoeboxValues.copy(messageCount = Some(messageCount), popularKeeper = Some(popularKeeper))
      }
    }
  }
}
