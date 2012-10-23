package com.keepit.model

import com.keepit.common.db.{CX, Id, Entity, EntityTable, ExternalId, State}
import com.keepit.common.db.NotFoundException
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import ru.circumflex.orm._
import java.net.URI
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import scala.collection.mutable

case class BookmarkSource(value: String) {
  implicit def getValue = value
  implicit def source(value: String) = BookmarkSource(value)
  override def toString = value
}

object BookmarkSource {
  implicit def source(value: String) = BookmarkSource(value)
}

case class Bookmark(
  id: Option[Id[Bookmark]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Bookmark] = ExternalId(),
  title: String,
  url: String,
  uriId: Id[NormalizedURI],
  bookmarkPath: Option[String] = None,
  isPrivate: Boolean = false,
  userId: Id[User],
  state: State[Bookmark] = Bookmark.States.ACTIVE,
  source: BookmarkSource
) {
  
  def save(implicit conn: Connection): Bookmark = {
    val entity = BookmarkEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
  
  def delete()(implicit conn: Connection): Unit = {
    val res = (BookmarkEntity AS "b").map { b => DELETE (b) WHERE (b.id EQ this.id.get) execute }
    if (res != 1) {
      throw new Exception("[%s] did not delete %s".format(res, this))
    }
  }
}

object Bookmark {
  
  def apply(uri: NormalizedURI, user: User, title: String, url: String, source: BookmarkSource): Bookmark = 
    Bookmark(title = title, url = url, userId = user.id.get, uriId = uri.id.get, source = source)
  
  def load(uri: NormalizedURI, user: User)(implicit conn: Connection): Option[Bookmark] = {
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE ((b.uriId EQ uri.id.get) AND (b.userId EQ user.id.get)) unique }.map( _.view )
  }
  
  def ofUri(uri: NormalizedURI)(implicit conn: Connection): Seq[Bookmark] = {
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.uriId EQ uri.id.get) list }.map( _.view )
  }
  
  def ofUser(user: User)(implicit conn: Connection): Seq[Bookmark] = {
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.userId EQ user.id.get) list }.map( _.view )
  }
  
  def all(implicit conn: Connection): Seq[Bookmark] =
    BookmarkEntity.all.map(_.view)
    
  def page(page: Int = 0, size: Int = 20)(implicit conn: Connection): Seq[Bookmark] =
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b LIMIT size OFFSET (page * size) ORDER_BY (b.createdAt DESC) list }.map( _.view )
  
  def get(id: Id[Bookmark])(implicit conn: Connection): Bookmark =
    getOpt(id).getOrElse(throw NotFoundException(id))
    
  def getOpt(id: Id[Bookmark])(implicit conn: Connection): Option[Bookmark] =
    BookmarkEntity.get(id).map(_.view)
    
  def get(externalId: ExternalId[Bookmark])(implicit conn: Connection): Bookmark =
    getOpt(externalId).getOrElse(throw NotFoundException(externalId))
  
  def getOpt(externalId: ExternalId[Bookmark])(implicit conn: Connection): Option[Bookmark] =
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.externalId EQ externalId) unique }.map(_.view)
    
  object States {
    val ACTIVE = State[Bookmark]("active")
    val INACTIVE = State[Bookmark]("inactive")
  }
}

private[model] class BookmarkEntity extends Entity[Bookmark, BookmarkEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[Bookmark].NOT_NULL(ExternalId())
  val title = "title".VARCHAR(256).NOT_NULL
  val url = "url".VARCHAR(256).NOT_NULL
  val uriId = "uri_id".ID[NormalizedURI].NOT_NULL
  val state = "state".STATE[Bookmark].NOT_NULL(Bookmark.States.ACTIVE)
  val bookmarkPath = "bookmark_path".VARCHAR(512).NOT_NULL
  val userId = "user_id".ID[User]
  val isPrivate = "is_private".BOOLEAN.NOT_NULL
  val source = "source".VARCHAR(256).NOT_NULL
  
  def relation = BookmarkEntity
  
  def view(implicit conn: Connection): Bookmark = Bookmark(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    title = title(),
    url = url(),
    state = state(),
    uriId = uriId(),
    isPrivate = isPrivate(),
    userId = userId(),
    bookmarkPath = bookmarkPath.value,
    source = source()
  )
}

private[model] object BookmarkEntity extends BookmarkEntity with EntityTable[Bookmark, BookmarkEntity] {
  override def relationName = "bookmark"
  
  def apply(view: Bookmark): BookmarkEntity = {
    val bookmark = new BookmarkEntity
    bookmark.id.set(view.id)
    bookmark.createdAt := view.createdAt
    bookmark.updatedAt := view.updatedAt
    bookmark.externalId := view.externalId
    bookmark.title := view.title
    bookmark.url := view.url
    bookmark.state := view.state
    bookmark.uriId := view.uriId
    bookmark.bookmarkPath.set(view.bookmarkPath)
    bookmark.isPrivate := view.isPrivate
    bookmark.userId.set(view.userId)
    bookmark.source := view.source.value
    bookmark
  }
}


