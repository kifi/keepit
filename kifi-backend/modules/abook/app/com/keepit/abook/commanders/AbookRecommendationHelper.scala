package com.keepit.abook.commanders

import java.text.Normalizer

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.service.RequestConsolidator
import com.keepit.graph.GraphServiceClient
import com.keepit.graph.model.SociallyRelatedEntitiesForOrg
import com.keepit.model.{ User, Organization }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class AbookRecommendationHelper @Inject() (shoebox: ShoeboxServiceClient) {
  def getNormalizedUsernames(id: Either[Id[User], Id[Organization]]): Future[Set[String]] = {
    val fConnectionIds = id match {
      case Left(userId) => shoebox.getFriends(userId)
      case Right(orgId) => shoebox.getOrganizationMembers(orgId)
    }
    for {
      connectionIds <- fConnectionIds
      basicUsers <- shoebox.getBasicUsers(connectionIds.toSeq)
    } yield {
      val fullNames = basicUsers.values.flatMap(user => Set(user.firstName + " " + user.lastName, user.lastName + " " + user.firstName))
      fullNames.toSet.map(normalize)
    }
  }

  val diacriticalMarksRegex = "\\p{InCombiningDiacriticalMarks}+".r
  @inline def normalize(fullName: String): String = diacriticalMarksRegex.replaceAllIn(Normalizer.normalize(fullName.trim, Normalizer.Form.NFD), "").toLowerCase
}
