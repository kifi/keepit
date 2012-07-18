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

case class Bookmark(
  id: Option[Id[Bookmark]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Bookmark] = ExternalId(),
  title: String,
  url: String,
  normalizedUrl: String,
  urlHash: String,
  bookmarkPath: Option[String] = None,
  isPrivate: Boolean = false,
  userId: Option[Id[User]] = None,
  state: State[Bookmark] = Bookmark.States.ACTIVE
) {
  
  def save(implicit conn: Connection): Bookmark = {
    val entity = BookmarkEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
  
  def loadUsingHash(implicit conn: Connection): Option[Bookmark] =
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE ((b.userId EQ userId.get) AND (b.urlHash EQ urlHash)) unique }.map(_.view)
}

object Bookmark {
  
  def apply(title: String, url: String, user: User): Bookmark = {
    //better: use http://stackoverflow.com/a/4057470/81698
    val normalized = new URI(url).normalize().toString()
    val binaryHash = MessageDigest.getInstance("MD5").digest(normalized.getBytes("UTF-8"))
    val hash = new String(new Base64().encode(binaryHash), "UTF-8")
    Bookmark(title = title, url = url, normalizedUrl = normalized, urlHash = hash, userId = user.id) 
  }
  
  //Used for admin, checking that we can talk with the db
  def loadTest()(implicit conn: Connection): Unit = {
    val bookmark: Option[BookmarkEntity] = (BookmarkEntity AS "b").map { u =>
      SELECT (u.*) FROM u LIMIT 1
    } unique;
    bookmark.get.view
  }

  def search(term: String)(implicit conn: Connection): Map[Bookmark, Int] = {
    val res = new mutable.HashMap[Bookmark, Int]() {
      override def default(key: Bookmark): Int = 0
    }
    term.split("\\s") map {_.toLowerCase()} flatMap { token =>
      (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.title ILIKE ("%" + token + "%")) }.list.map( _.view )
    } foreach { bookmark =>
      res(bookmark) += 1
    }
    res.toMap
  }
  
  def all(implicit conn: Connection): Seq[Bookmark] =
    BookmarkEntity.all.map(_.view)
  
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
  val state = "state".STATE[Bookmark].NOT_NULL(Bookmark.States.ACTIVE)
  val normalizedUrl = "normalized_url".VARCHAR(16).NOT_NULL
  val urlHash = "url_hash".VARCHAR(512).NOT_NULL
  val bookmarkPath = "bookmark_path".VARCHAR(512).NOT_NULL
  val userId = "user_id".ID[User]
  val isPrivate = "is_private".BOOLEAN.NOT_NULL
  
  def relation = BookmarkEntity
  
  def view(implicit conn: Connection): Bookmark = Bookmark(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    title = title(),
    url = url(),
    state = state(),
    urlHash = urlHash(),
    normalizedUrl = normalizedUrl(),
    isPrivate = isPrivate(),
    userId = userId.value,
    bookmarkPath = bookmarkPath.value
  )
}

private[model] object BookmarkEntity extends BookmarkEntity with EntityTable[Bookmark, BookmarkEntity] {
  override def relationName = "Bookmark"
  
  def apply(view: Bookmark): BookmarkEntity = {
    val bookmark = new BookmarkEntity
    bookmark.id.set(view.id)
    bookmark.createdAt := view.createdAt
    bookmark.updatedAt := view.updatedAt
    bookmark.externalId := view.externalId
    bookmark.title := view.title
    bookmark.url := view.url
    bookmark.state := view.state
    bookmark.urlHash := view.urlHash
    bookmark.normalizedUrl := view.normalizedUrl
    bookmark.bookmarkPath.set(view.bookmarkPath)
    bookmark.isPrivate := view.isPrivate
    bookmark.userId.set(view.userId)
    bookmark
  }
}


