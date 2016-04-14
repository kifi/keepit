package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.TagSorting
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.discussion.Message
import org.joda.time.DateTime
import play.api.libs.functional.syntax.unlift

import scala.collection.{ Traversable, Iterable }

@ImplementedBy(classOf[KeepTagRepoImpl])
trait KeepTagRepo extends Repo[KeepTag] {
  def getByKeepIds(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[KeepTag]]
  def getTagsByUser(userId: Id[User], offset: Int, pageSize: Int, sort: TagSorting)(implicit session: RSession): Seq[(Hashtag, Int)]
  def getByMessage(messageId: Id[Message])(implicit session: RSession): Seq[KeepTag]
  def getAllByTagAndUser(tag: Hashtag, userId: Id[User])(implicit session: RSession): Seq[KeepTag]
  def removeTagsFromKeep(keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int
  def deactivate(model: KeepTag)(implicit session: RWSession): Unit
  def countForUser(userId: Id[User])(implicit session: RSession): Int
}

@Singleton
class KeepTagRepoImpl @Inject() (
  airbrake: AirbrakeNotifier,
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[KeepTag] with KeepTagRepo {

  import db.Driver.simple._

  override def invalidateCache(ktc: KeepTag)(implicit session: RSession): Unit = deleteCache(ktc)

  override def deleteCache(ktc: KeepTag)(implicit session: RSession): Unit = {}

  type RepoImpl = KeepTagTable
  class KeepTagTable(tag: Tag) extends RepoTable[KeepTag](db, tag, "keep_tag") {
    implicit val tagMapper = MappedColumnType.base[Hashtag, String](_.tag, Hashtag.apply)

    def tagName = column[Hashtag]("tag", O.NotNull)
    def normalizedTag = column[String]("normalized_tag", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def messageId = column[Option[Id[Message]]]("message_id", O.Nullable)
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)

    def * = (id.?, state, createdAt, updatedAt, tagName, normalizedTag, keepId, messageId, userId) <> (applyFromDbRow, unapplyToDbRow)
  }

  def applyFromDbRow(row: ((Option[Id[KeepTag]], State[KeepTag], DateTime, DateTime, Hashtag, String, Id[Keep], Option[Id[Message]], Option[Id[User]]))) = {
    KeepTag(row._1, row._2, row._3, row._4, row._5, row._7, row._8, row._9)
  }

  def unapplyToDbRow(kt: KeepTag): Option[(Option[Id[KeepTag]], State[KeepTag], DateTime, DateTime, Hashtag, String, Id[Keep], Option[Id[Message]], Option[Id[User]])] = {
    Some((kt.id, kt.state, kt.createdAt, kt.updatedAt, kt.tag, kt.tag.normalized, kt.keepId, kt.messageId, kt.userId))
  }

  def table(tag: Tag) = new KeepTagTable(tag)
  initTable()

  private def activeRows = rows.filter(_.state === KeepTagStates.ACTIVE)

  def getByKeepIds(keepIds: Traversable[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[KeepTag]] = {
    activeRows.filter(row => row.keepId.inSet(keepIds)).list.groupBy(_.keepId).withDefaultValue(Seq.empty)
  }

  // todo: Sorting, pagination
  def getTagsByUser(userId: Id[User], offset: Int, pageSize: Int, sort: TagSorting)(implicit session: RSession): Seq[(Hashtag, Int)] = {
    val pp = activeRows.filter(row => row.userId === userId).groupBy(_.normalizedTag)
      .map { case (normalizedTag, q) => (q.map(_.tagName).max, q.map(_.createdAt).max, q.length) }
      .sortBy { s: (Column[Option[Hashtag]], Column[Option[DateTime]], Column[Int]) =>
        sort match {
          case TagSorting.LastKept => s._2.desc
          case TagSorting.Name => s._1.desc
          case TagSorting.NumKeeps => s._3.desc
        }
      }

    pp.drop(offset)
      .take(pageSize)
      .list
      .collect { case (Some(t), Some(m), b) => (t, b) }
  }

  def getByMessage(messageId: Id[Message])(implicit session: RSession): Seq[KeepTag] = {
    activeRows.filter(row => row.messageId === messageId).list
  }

  def getAllByTagAndUser(tag: Hashtag, userId: Id[User])(implicit session: RSession): Seq[KeepTag] = {
    activeRows.filter(row => row.normalizedTag === tag.normalized && row.userId === userId).list
  }

  def removeTagsFromKeep(keepIds: Traversable[Id[Keep]], tags: Traversable[Hashtag])(implicit session: RWSession): Int = {
    val normalized = tags.map(_.normalized)
    val toKill = activeRows.filter(row => row.keepId.inSet(keepIds) && row.normalizedTag.inSet(normalized)).list
    toKill.foreach(deactivate)
    toKill.length
  }

  def deactivate(model: KeepTag)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }

  def countForUser(userId: Id[User])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    import scala.collection.JavaConversions._
    sql"select count(*) from keep_tag where user_id = $userId".as[Int].first
  }

}

