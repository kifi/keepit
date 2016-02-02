package com.keepit.commanders

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.common.core.tryExtensionOps
import play.api.libs.json.{ Format, Json }

import scala.util.Try

case class LibraryQuery(
    target: LibraryQuery.Target,
    arrangement: Option[LibraryQuery.Arrangement] = None,
    fromId: Option[Id[Library]],
    offset: Int,
    limit: Int) {
  def withArrangement(newArrangement: LibraryQuery.Arrangement) = this.copy(arrangement = Some(newArrangement))
}

object LibraryQuery {
  sealed abstract class Target
  case class ForUser(userId: Id[User], roles: Set[LibraryAccess]) extends Target
  case class ForOrg(orgId: Id[Organization]) extends Target
  case class Arrangement(ordering: LibraryOrdering, direction: SortDirection)
  object Arrangement {
    implicit val format: Format[Arrangement] = Json.format[Arrangement]
    val GLOBAL_DEFAULT = Arrangement(LibraryOrdering.LAST_KEPT_INTO, SortDirection.DESCENDING)
  }

  case class ExtraInfo(explicitlyAllowedLibraries: Set[Id[Library]], orgsWithVisibleLibraries: Set[Id[Organization]])
}

@ImplementedBy(classOf[LibraryQueryCommanderImpl])
trait LibraryQueryCommander {
  def setPreferredArrangement(userId: Id[User], arrangement: LibraryQuery.Arrangement): Unit
  def getLibraries(requester: Option[Id[User]], query: LibraryQuery)(implicit session: RSession): Seq[Id[Library]]
}

@Singleton
class LibraryQueryCommanderImpl @Inject() (
  db: Database,
  userValueRepo: UserValueRepo,
  libMembershipRepo: LibraryMembershipRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  libRepo: LibraryRepo,
  implicit val airbrake: AirbrakeNotifier)
    extends LibraryQueryCommander {

  def setPreferredArrangement(userId: Id[User], arrangement: LibraryQuery.Arrangement): Unit = db.readWrite { implicit s =>
    userValueRepo.setValue(userId, UserValueName.DEFAULT_LIBRARY_ARRANGEMENT, Json.stringify(Json.toJson(arrangement)))
  }

  def getLibraries(requester: Option[Id[User]], query: LibraryQuery)(implicit session: RSession): Seq[Id[Library]] = {
    val preferredArrangement = query.arrangement.orElse {
      for {
        userId <- requester
        preferredArrangementStr <- userValueRepo.getUserValue(userId, UserValueName.DEFAULT_LIBRARY_ARRANGEMENT).map(_.value)
        preferredArrangementJson <- Try(Json.parse(preferredArrangementStr)).safeOption
        preferredArrangement <- Try(preferredArrangementJson.as[LibraryQuery.Arrangement]).safeOption
      } yield preferredArrangement
    }
    val customizedQuery = preferredArrangement.fold(query)(query.withArrangement)

    val extraInfo = LibraryQuery.ExtraInfo(
      explicitlyAllowedLibraries = requester.map(userId => libMembershipRepo.getWithUserId(userId).map(_.libraryId).toSet).getOrElse(Set.empty),
      orgsWithVisibleLibraries = requester.map(userId => orgMembershipRepo.getAllByUserId(userId).map(_.organizationId).toSet).getOrElse(Set.empty)
    )
    libRepo.getLibraryIdsForQuery(customizedQuery, extraInfo)
  }

}

