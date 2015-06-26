package com.keepit.rover.model

import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.model._
import org.joda.time.DateTime
import com.keepit.common.time._
import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ VersionNumber, State, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier

@ImplementedBy(classOf[ArticleImageRepoImpl])
trait ArticleImageRepo extends Repo[ArticleImage] {
  def getByUriAndKind[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], excludeState: Option[State[ArticleImage]] = Some(ArticleImageStates.INACTIVE))(implicit session: RSession): Set[ArticleImage]
  def getByUrlAndKind[A <: Article](url: String, kind: ArticleKind[A], excludeState: Option[State[ArticleImage]] = Some(ArticleImageStates.INACTIVE))(implicit session: RSession): Set[ArticleImage]
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[ArticleImage]] = Some(ArticleImageStates.INACTIVE))(implicit session: RSession): Set[ArticleImage]
  def getByUris(uriIds: Set[Id[NormalizedURI]], excludeState: Option[State[ArticleImage]] = Some(ArticleImageStates.INACTIVE))(implicit session: RSession): Map[Id[NormalizedURI], Set[ArticleImage]]
  def getByArticleInfo(articleInfo: ArticleInfo)(implicit session: RSession): Set[ArticleImage]
  def intern[A <: Article](url: String, uriId: Id[NormalizedURI], kind: ArticleKind[A], imageHash: ImageHash, imageUrl: String, version: ArticleVersion)(implicit session: RWSession): ArticleImage
}

@Singleton
class ArticleImageRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier,
    articleImagesCache: RoverArticleImagesCache) extends DbRepo[ArticleImage] with ArticleImageRepo with Logging {

  import db.Driver.simple._

  type RepoImpl = ArticleImageTable
  class ArticleImageTable(tag: Tag) extends RepoTable[ArticleImage](db, tag, "article_image") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def urlHash = column[UrlHash]("url_hash", O.NotNull)
    def kind = column[String]("kind", O.NotNull)
    def versionMajor = column[VersionNumber[Article]]("version_major", O.NotNull)
    def versionMinor = column[VersionNumber[Article]]("version_minor", O.NotNull)
    def fetchedAt = column[DateTime]("fetched_at", O.NotNull)
    def imageUrl = column[String]("image_url", O.NotNull)
    def imageHash = column[ImageHash]("image_hash", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, uriId, url, urlHash, kind, versionMajor, versionMinor, fetchedAt, imageUrl, imageHash) <> ((ArticleImage.applyFromDbRow _).tupled, ArticleImage.unapplyToDbRow _)
  }

  def table(tag: Tag) = new ArticleImageTable(tag)
  initTable()

  override def deleteCache(model: ArticleImage)(implicit session: RSession): Unit = {
    articleImagesCache.remove(RoverArticleImagesKey(model.uriId, model.articleKind))
  }

  override def invalidateCache(model: ArticleImage)(implicit session: RSession): Unit = {
    deleteCache(model)
  }

  def getByUriAndKind[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], excludeState: Option[State[ArticleImage]] = Some(ArticleImageStates.INACTIVE))(implicit session: RSession): Set[ArticleImage] = {
    (for (r <- rows if r.uriId === uriId && r.kind === kind.typeCode && r.state =!= excludeState.orNull) yield r).list.toSet
  }

  def getByUrlAndKind[A <: Article](url: String, kind: ArticleKind[A], excludeState: Option[State[ArticleImage]] = Some(ArticleImageStates.INACTIVE))(implicit session: RSession): Set[ArticleImage] = {
    val urlHash = UrlHash.hashUrl(url)
    (for (r <- rows if r.urlHash === urlHash && r.url === url && r.kind === kind.typeCode && r.state =!= excludeState.orNull) yield r).list.toSet
  }

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[ArticleImage]] = Some(ArticleImageStates.INACTIVE))(implicit session: RSession): Set[ArticleImage] = {
    (for (r <- rows if r.uriId === uriId && r.state =!= excludeState.orNull) yield r).list.toSet
  }

  def getByUris(uriIds: Set[Id[NormalizedURI]], excludeState: Option[State[ArticleImage]] = Some(ArticleImageStates.INACTIVE))(implicit session: RSession): Map[Id[NormalizedURI], Set[ArticleImage]] = {
    val existingByUriId = (for (r <- rows if r.uriId.inSet(uriIds) && r.state =!= excludeState.orNull) yield r).list.toSet[ArticleImage].groupBy(_.uriId)
    val missingUriIds = uriIds -- existingByUriId.keySet
    existingByUriId ++ missingUriIds.map(_ -> Set.empty[ArticleImage])
  }

  def getByArticleInfo(articleInfo: ArticleInfo)(implicit session: RSession): Set[ArticleImage] = {
    getByUrlAndKind(articleInfo.url, articleInfo.articleKind)
  }

  private def getByUriAndKindAndImageHash[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], imageHash: ImageHash)(implicit session: RSession): Option[ArticleImage] = {
    val q = (for (r <- rows if r.uriId === uriId && r.kind === kind.typeCode && r.imageHash === imageHash) yield r)
    q.firstOption
  }

  def intern[A <: Article](url: String, uriId: Id[NormalizedURI], kind: ArticleKind[A], imageHash: ImageHash, imageUrl: String, version: ArticleVersion)(implicit session: RWSession): ArticleImage = {
    getByUriAndKindAndImageHash(uriId, kind, imageHash) match {
      case Some(existingImage) => {
        val updatedImage = existingImage.copy(state = ArticleImageStates.ACTIVE, imageUrl = imageUrl, version = version, fetchedAt = currentDateTime)
        save(updatedImage)
      }
      case None => {
        val newImage = ArticleImage(url, uriId, kind, version, imageUrl, imageHash)
        save(newImage)
      }
    }
  }
}
