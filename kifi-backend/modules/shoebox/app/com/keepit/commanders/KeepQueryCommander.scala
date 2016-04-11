package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.core.tryExtensionOps
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import play.api.libs.json.{ Format, Json }

import scala.util.Try

case class KeepQuery(
    target: KeepQuery.Target,
    arrangement: Option[KeepQuery.Arrangement],
    paging: KeepQuery.Paging) {
  def withArrangement(newArrangement: KeepQuery.Arrangement) = this.copy(arrangement = Some(newArrangement))
  def withLimit(newLimit: Int) = this.copy(paging = paging.copy(limit = Limit(newLimit)))
  def fromId(id: Id[Keep]) = this.copy(paging = paging.copy(fromId = Some(id)))
}

object KeepQuery {
  sealed abstract class Target
  case class ForLibrary(libId: Id[Library]) extends Target

  final case class Paging(fromId: Option[Id[Keep]], offset: Offset, limit: Limit)
  final case class Arrangement(ordering: KeepOrdering, direction: SortDirection)
  object Arrangement {
    implicit val format: Format[Arrangement] = Json.format[Arrangement]
    val GLOBAL_DEFAULT = Arrangement(KeepOrdering.LAST_ACTIVITY_AT, SortDirection.DESCENDING)
    def fromOptions(ord: Option[KeepOrdering], dir: Option[SortDirection]): Option[Arrangement] = (ord, dir) match {
      case (None, None) => None
      case (Some(o), None) => Some(Arrangement(o, GLOBAL_DEFAULT.direction))
      case (None, Some(d)) => Some(Arrangement(GLOBAL_DEFAULT.ordering, d))
      case (Some(o), Some(d)) => Some(Arrangement(o, d))
    }
    implicit class FromOrdering(ko: KeepOrdering) {
      def desc = Arrangement(ko, SortDirection.DESCENDING)
      def asc = Arrangement(ko, SortDirection.ASCENDING)
    }
  }
}

@ImplementedBy(classOf[KeepQueryCommanderImpl])
trait KeepQueryCommander {
  def setPreferredArrangement(userId: Id[User], arrangement: KeepQuery.Arrangement): Unit
  def getKeeps(requester: Option[Id[User]], query: KeepQuery)(implicit session: RSession): Seq[Id[Keep]]
}

@Singleton
class KeepQueryCommanderImpl @Inject() (
  db: Database,
  userValueRepo: UserValueRepo,
  keepRepo: KeepRepo,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val airbrake: AirbrakeNotifier)
    extends KeepQueryCommander {

  def setPreferredArrangement(userId: Id[User], arrangement: KeepQuery.Arrangement): Unit = db.readWrite { implicit s =>
    userValueRepo.setValue(userId, UserValueName.DEFAULT_KEEP_ARRANGEMENT, Json.stringify(Json.toJson(arrangement)))
  }

  def getKeeps(requester: Option[Id[User]], query: KeepQuery)(implicit session: RSession): Seq[Id[Keep]] = {
    val preferredArrangement = query.arrangement.orElse {
      for {
        userId <- requester
        preferredArrangementStr <- userValueRepo.getUserValue(userId, UserValueName.DEFAULT_KEEP_ARRANGEMENT).map(_.value)
        preferredArrangementJson <- Try(Json.parse(preferredArrangementStr)).airbrakingOption
        preferredArrangement <- Try(preferredArrangementJson.as[KeepQuery.Arrangement]).airbrakingOption
      } yield preferredArrangement
    }
    val customizedQuery = preferredArrangement.fold(query)(query.withArrangement)
    keepRepo.getKeepIdsForQuery(customizedQuery)
  }
}
