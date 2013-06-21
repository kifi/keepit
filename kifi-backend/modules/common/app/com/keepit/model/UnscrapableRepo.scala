package com.keepit.model

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.RSession
import scala.Some

@ImplementedBy(classOf[UnscrapableRepoImpl])
trait UnscrapableRepo extends Repo[Unscrapable] {
  def allActive()(implicit session: RSession): Seq[Unscrapable]
  def contains(url: String)(implicit session: RSession): Boolean
}

@Singleton
class UnscrapableRepoImpl @Inject() (
                                      val db: DataBaseComponent,
                                      val clock: Clock,
                                      val unscrapableCache: UnscrapableAllCache)
  extends DbRepo[Unscrapable] with UnscrapableRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._
  import scala.util.matching.Regex

  override val table = new RepoTable[Unscrapable](db, "unscrapable") {
    def pattern = column[String]("pattern", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ pattern ~ state <> (Unscrapable, Unscrapable.unapply _)
  }

  private var allMemCache: Option[Seq[Unscrapable]] = None

  override def invalidateCache(unscrapable: Unscrapable)(implicit session: RSession) = {
    unscrapableCache.remove(UnscrapableAllKey())
    allMemCache = None
    unscrapable
  }

  def allActive()(implicit session: RSession): Seq[Unscrapable] =
    allMemCache.getOrElse {
      val result = unscrapableCache.getOrElse(UnscrapableAllKey()) {
        (for(f <- table if f.state === UnscrapableStates.ACTIVE) yield f).list
      }
      allMemCache = Some(result)
      result
    }

  def contains(url: String)(implicit session: RSession): Boolean = {
    !allActive().forall { s =>
      !url.matches(s.pattern)
    }
  }
}
