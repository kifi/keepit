package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{FortyTwoTypeMappers, DbRepo, DataBaseComponent, Repo}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[UriTopicRepoImpl])
trait UriTopicRepo extends Repo[UriTopic]{
  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession):Option[UriTopic]
  def getAssignedTopicsByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[(Option[Int], Option[Int])]
}

@Singleton
class UriTopicRepoImpl @Inject() (
                                   val db: DataBaseComponent,
                                   val clock: Clock
                                   ) extends DbRepo[UriTopic] with UriTopicRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UriTopic](db, "uri_topic"){
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def topic = column[Array[Byte]]("topic", O.NotNull)
    def primaryTopic = column[Option[Int]]("primary_topic")
    def secondaryTopic = column[Option[Int]]("secondary_topic")
    def * = id.? ~ uriId ~ topic ~ primaryTopic ~ secondaryTopic ~ createdAt ~ updatedAt <> (UriTopic, UriTopic.unapply _)
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[UriTopic] = {
    (for(r <- table if r.uriId === uriId) yield r).firstOption
  }

  def getAssignedTopicsByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[(Option[Int], Option[Int])] = {
    (for(r <- table if r.uriId === uriId) yield (r.primaryTopic, r.secondaryTopic)).firstOption
  }

}
