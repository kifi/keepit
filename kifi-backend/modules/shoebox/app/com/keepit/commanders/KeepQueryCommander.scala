package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.core.tryExtensionOps
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.EnumFormat
import com.keepit.common.reflection.Enumerator
import com.keepit.model._
import play.api.libs.json.{ Format, Json }
import play.api.mvc.QueryStringBindable

import scala.util.Try

case class KeepQuery(
    target: KeepQuery.Target,
    arrangement: Option[KeepQuery.Arrangement] = None,
    fromId: Option[Id[Keep]],
    offset: Offset,
    limit: Limit) {
  def withArrangement(newArrangement: KeepQuery.Arrangement) = this.copy(arrangement = Some(newArrangement))
}

object KeepQuery {
  sealed abstract class Target
  case class ForLibrary(libId: Id[Library]) extends Target

  case class Arrangement(ordering: KeepOrdering, direction: SortDirection)
  object Arrangement {
    implicit val format: Format[Arrangement] = Json.format[Arrangement]
    val GLOBAL_DEFAULT = Arrangement(KeepOrdering.LAST_ACTIVITY_AT, SortDirection.DESCENDING)
  }
}

sealed abstract class KeepOrdering(val value: String)

object KeepOrdering extends Enumerator[KeepOrdering] {
  case object LAST_ACTIVITY_AT extends KeepOrdering("last_activity_at")
  case object KEPT_AT extends KeepOrdering("kept_at")

  val all = _all
  def fromStr(str: String): Option[KeepOrdering] = all.find(_.value == str)
  def apply(str: String): KeepOrdering = fromStr(str).get

  implicit val format: Format[KeepOrdering] = EnumFormat.format(fromStr, _.value)

  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[KeepOrdering] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, KeepOrdering]] = {
      stringBinder.bind(key, params).map {
        case Left(_) => Left("Could not bind a KeepOrdering")
        case Right(str) => fromStr(str).map(ord => Right(ord)).getOrElse(Left("Could not bind a KeepOrdering"))
      }
    }
    override def unbind(key: String, ordering: KeepOrdering): String = {
      stringBinder.unbind(key, ordering.value)
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
