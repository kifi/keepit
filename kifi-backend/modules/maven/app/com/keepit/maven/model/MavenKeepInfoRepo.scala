package com.keepit.maven.model


import com.keepit.common.db.slick.{DbRepo, DataBaseComponent}
import com.keepit.common.db.Id
import com.keepit.model.{User, NormalizedURI, Keep}
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}

import com.google.inject.{ImplementedBy, Singleton, Inject}

import org.joda.time.DateTime


@ImplementedBy(classOf[MavenKeepInfoRepoImpl])
trait MavenKeepInfoRepo extends DbRepo[MavenKeepInfo] {
}



@Singleton
class MavenKeepInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
extends DbRepo[MavenKeepInfo] with MavenKeepInfoRepo {

  import db.Driver.simple._

  type RepoImpl = MavenKeepInfoTable
  class MavenKeepInfoTable(tag: Tag) extends RepoTable[MavenKeepInfo](db, tag, "maven_keep_info") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def isPrivate = column[Boolean]("is_private", O.NotNull)
    def * = (id.?,createdAt,updatedAt,uriId,userId,keepId,isPrivate,state) <> ((MavenKeepInfo.apply _).tupled, MavenKeepInfo.unapply _)
  }

  def table(tag:Tag) = new MavenKeepInfoTable(tag)
  initTable()

  def deleteCache(model: MavenKeepInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: MavenKeepInfo)(implicit session: RSession): Unit = {}

}
