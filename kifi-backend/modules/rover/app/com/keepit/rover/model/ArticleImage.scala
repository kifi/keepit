package com.keepit.rover.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.rover.article.{ ArticleKind, Article }
import org.joda.time.DateTime

object ArticleImageStates extends States[ArticleImage]

case class ArticleImage(
    id: Option[Id[ArticleImage]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ArticleImage] = ArticleImageStates.ACTIVE,
    uriId: Id[NormalizedURI],
    kind: String,
    version: ArticleVersion,
    fetchedAt: DateTime,
    imageUrl: String,
    imageHash: ImageHash) extends Model[ArticleImage] with ArticleKindHolder {
  def withId(id: Id[ArticleImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

object ArticleImage {
  def apply(uriId: Id[NormalizedURI], kind: ArticleKind[_], version: ArticleVersion, imageUrl: String, imageHash: ImageHash): ArticleImage = {
    ArticleImage(uriId = uriId, kind = kind.typeCode, version = version, fetchedAt = currentDateTime, imageUrl = imageUrl, imageHash = imageHash)
  }

  def applyFromDbRow(
    id: Option[Id[ArticleImage]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ArticleImage] = ArticleImageStates.ACTIVE,
    uriId: Id[NormalizedURI],
    kind: String,
    versionMajor: VersionNumber[Article],
    versionMinor: VersionNumber[Article],
    fetchedAt: DateTime,
    imageUrl: String,
    imageHash: ImageHash): ArticleImage = {
    val version = ArticleVersion(versionMajor, versionMinor)
    ArticleImage(id, createdAt, updatedAt, state, uriId, kind, version, fetchedAt, imageUrl, imageHash)
  }

  def unapplyToDbRow(articleImage: ArticleImage) = {
    Some(
      articleImage.id,
      articleImage.createdAt,
      articleImage.updatedAt,
      articleImage.state,
      articleImage.uriId,
      articleImage.kind,
      articleImage.version.major,
      articleImage.version.minor,
      articleImage.fetchedAt,
      articleImage.imageUrl,
      articleImage.imageHash
    )
  }
}

