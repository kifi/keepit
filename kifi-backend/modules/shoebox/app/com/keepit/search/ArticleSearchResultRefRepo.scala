package com.keepit.search

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.ExternalId

@ImplementedBy(classOf[ArticleSearchResultRefRepoImpl])
trait ArticleSearchResultRefRepo extends Repo[ArticleSearchResultRef] with ExternalIdColumnFunction[ArticleSearchResultRef]


@Singleton
class ArticleSearchResultRefRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[ArticleSearchResultRef] with ArticleSearchResultRefRepo with ExternalIdColumnDbFunction[ArticleSearchResultRef] {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[ArticleSearchResultRef](db, "article_search_result") with ExternalIdColumn[ArticleSearchResultRef] {
    def last = column[ExternalId[ArticleSearchResultRef]]("last", O.Nullable)
    def myTotal = column[Int]("my_total", O.NotNull)
    def friendsTotal = column[Int]("friends_total", O.NotNull)
    def mayHaveMoreHits = column[Boolean]("may_have_more_hits", O.NotNull)
    def millisPassed = column[Int]("millis_passed", O.NotNull)
    def hitCount = column[Int]("hit_count", O.NotNull)
    def pageNumber = column[Int]("page_number", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ state ~ last.? ~ myTotal ~ friendsTotal ~ mayHaveMoreHits ~ millisPassed ~ hitCount ~ pageNumber <> (ArticleSearchResultRef.apply _, ArticleSearchResultRef.unapply _)
  }
}
