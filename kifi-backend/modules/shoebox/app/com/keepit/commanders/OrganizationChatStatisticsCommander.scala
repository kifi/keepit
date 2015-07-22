package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.commanders.OrganizationChatStatisticsCommander.EngagementStat
import com.keepit.common.db.Id
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.{User, Organization, OrganizationRepo}

import scala.concurrent.Future

@Singleton
class OrganizationChatStatisticsCommander @Inject() (
  val orgRepo: OrganizationRepo,
  val elizaServiceClient: ElizaServiceClient
) {
  
  val internalChats = new EngagementStat {

    override def detailed(userIds: Set[Id[User]]): Future[Seq[(Int, Int)]] =
      elizaServiceClient.getSharedThreadsForGroupByWeek(userIds)

  }

  val allChats = new EngagementStat {

    override def detailed(userIds: Set[Id[User]]): Future[Seq[(Int, Int)]] =
      elizaServiceClient.getAllThreadsForGroupByWeek(userIds)

  }

}

object OrganizationChatStatisticsCommander {

  trait EngagementStat {

    def summary(userIds: Set[Id[User]]): Future[Int] =
      for {
        detailed <- detailed(userIds)
      } yield {
        detailed.map {
          case (week, stat) => stat
        }.sum
      }

    def detailed(userIds: Set[Id[User]]): Future[Seq[(Int, Int)]]
    
//    def getBothForOrganization(userIds: Seq[Id[User]]): Future[(Seq[(Int, Int)], Int)] = {
//      for {
//        detailed <- detailed(userIds)
//        general <- summary(userIds)
//      } yield (detailed, general)
//    }

  }

}
