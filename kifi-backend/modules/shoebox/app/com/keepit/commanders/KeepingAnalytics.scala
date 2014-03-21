package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.heimdal._
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.google.inject.{Singleton, Inject}
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class KeepingAnalytics @Inject() (heimdal : HeimdalServiceClient) {
  def renamedTag(oldTag: Collection, newTag: Collection, context: HeimdalContext): Unit = {
    val renamedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= context.data
      contextBuilder += ("action", "renamedTag")
      contextBuilder += ("tagId", newTag.id.get.toString)
      heimdal.trackEvent(UserEvent(oldTag.userId, contextBuilder.build, UserEventTypes.KEPT, renamedAt))

      // Anonymized event with tag information
      anonymise(contextBuilder)
      contextBuilder += ("oldTagName", oldTag.name)
      contextBuilder += ("tagName", newTag.name)
      heimdal.trackEvent(AnonymousEvent(contextBuilder.build, AnonymousEventTypes.KEPT, renamedAt))
    }
  }

  def createdTag(newTag: Collection, context: HeimdalContext): Unit = {
    val createdAt = currentDateTime
    val isDefaultTag = context.get[String]("source").map(_ == KeepSource.default.value) getOrElse false
    if (!isDefaultTag) SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= context.data
      contextBuilder += ("action", "createdTag")
      contextBuilder += ("tagId", newTag.id.get.toString)
      heimdal.trackEvent(UserEvent(newTag.userId, contextBuilder.build, UserEventTypes.KEPT, createdAt))
      heimdal.incrementUserProperties(newTag.userId, "tags" -> 1)

      // Anonymized event with tag information
      anonymise(contextBuilder)
      contextBuilder += ("tagName", newTag.name)
      heimdal.trackEvent(AnonymousEvent(contextBuilder.build, AnonymousEventTypes.KEPT, createdAt))
    }
  }

  def deletedTag(oldTag: Collection, context: HeimdalContext): Unit = {
    val deletedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= context.data
      contextBuilder += ("action", "deletedTag")
      contextBuilder += ("tagId", oldTag.id.get.toString)
      heimdal.trackEvent(UserEvent(oldTag.userId, contextBuilder.build, UserEventTypes.KEPT, deletedAt))
      heimdal.incrementUserProperties(oldTag.userId, "tags" -> -1)
    }
  }

  def keptPages(userId: Id[User], keeps: Seq[Bookmark], existingContext: HeimdalContext): Unit = SafeFuture {
    val keptAt = currentDateTime

    keeps.collect { case bookmark if bookmark.source != KeepSource.default =>
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      contextBuilder += ("action", "keptPage")
      contextBuilder += ("source", bookmark.source.value)
      contextBuilder += ("isPrivate", bookmark.isPrivate)
      contextBuilder += ("hasTitle", bookmark.title.isDefined)
      contextBuilder += ("uriId", bookmark.uriId.toString)
      val context = contextBuilder.build
      heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.KEPT, keptAt))
      if (bookmark.source != KeepSource.bookmarkImport) heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.USED_KIFI, keptAt))

      // Anonymized event with page information
      anonymise(contextBuilder)
      contextBuilder.addUrlInfo(bookmark.url)
      heimdal.trackEvent(AnonymousEvent(contextBuilder.build, AnonymousEventTypes.KEPT, keptAt))
    }
    val kept = keeps.length
    val keptPrivate = keeps.count(_.isPrivate)
    val keptPublic = kept - keptPrivate
    heimdal.incrementUserProperties(userId, "keeps" -> kept, "privateKeeps" -> keptPrivate, "publicKeeps" -> keptPublic)
    heimdal.setUserProperties(userId, "lastKept" -> ContextDate(keptAt))
  }

  def keepImport(userId: Id[User], keptAt: DateTime, existingContext: HeimdalContext, countImported: Int): Unit = {
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      contextBuilder += ("action", "importedBookmarks")
      contextBuilder += ("importedBookmarks", countImported)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.JOINED, keptAt))
    }
  }

  def unkeptPages(userId: Id[User], keeps: Seq[Bookmark], context: HeimdalContext): Unit = {
    val unkeptAt = currentDateTime

    SafeFuture {
      keeps.foreach { keep =>
        val contextBuilder = new HeimdalContextBuilder
        contextBuilder.data ++= context.data
        contextBuilder += ("action", "unkeptPage")
        contextBuilder += ("keepSource", keep.source.value)
        contextBuilder += ("isPrivate", keep.isPrivate)
        contextBuilder += ("hasTitle", keep.title.isDefined)
        contextBuilder += ("uriId", keep.uriId.toString)
        heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.KEPT, unkeptAt))
      }
      val unkept = keeps.length
      val unkeptPrivate = keeps.count(_.isPrivate)
      val unkeptPublic = unkept - unkeptPrivate
      heimdal.incrementUserProperties(userId, "keeps" -> - unkept, "privateKeeps" -> - unkeptPrivate, "publicKeeps" -> - unkeptPublic)
    }
  }

  def updatedKeep(oldKeep: Bookmark, updatedKeep: Bookmark, context: HeimdalContext): Unit = SafeFuture {
    val contextBuilder = new HeimdalContextBuilder
    contextBuilder.data ++= context.data
    contextBuilder += ("action", "updatedKeep")
    contextBuilder += ("uriId", updatedKeep.uriId.toString)
    if (oldKeep.isPrivate != updatedKeep.isPrivate) {
      if (updatedKeep.isPrivate) {
        contextBuilder += ("updatedPrivacy", "private")
        heimdal.incrementUserProperties(updatedKeep.userId, "privateKeeps" -> 1, "publicKeeps" -> -1)
      } else {
        contextBuilder += ("updatedPrivacy", "public")
        heimdal.incrementUserProperties(updatedKeep.userId, "privateKeeps" -> -1, "publicKeeps" -> 1)
      }
    }

    if (oldKeep.title != updatedKeep.title) {
      val updatedTitle = if (oldKeep.title.isEmpty) "missing" else "different"
      contextBuilder += ("updatedTitle", updatedTitle)
    }

    heimdal.trackEvent(UserEvent(updatedKeep.userId, contextBuilder.build, UserEventTypes.KEPT, updatedKeep.updatedAt))

    // Anonymized event with page information
    if (contextBuilder.data.contains("updatedPrivacy")) {
      anonymise(contextBuilder)
      contextBuilder.addUrlInfo(updatedKeep.url)
      heimdal.trackEvent(AnonymousEvent(contextBuilder.build, AnonymousEventTypes.KEPT, updatedKeep.updatedAt))
    }

  }

  def taggedPage(tag: Collection, keep: Bookmark, context: HeimdalContext, taggedAt: DateTime = currentDateTime): Unit = {
    val isDefaultTag = context.get[String]("source").map(_ == KeepSource.default.value) getOrElse false
    if (!isDefaultTag) changedTag(tag, keep, "taggedPage", context, taggedAt)
  }

  def untaggedPage(tag: Collection, keep: Bookmark, context: HeimdalContext, untaggedAt: DateTime = currentDateTime): Unit =
    changedTag(tag, keep, "untaggedPage", context, untaggedAt)

  private def changedTag(tag: Collection, keep: Bookmark, action: String, context: HeimdalContext, changedAt: DateTime): Unit = SafeFuture {
    val contextBuilder = new HeimdalContextBuilder
    contextBuilder.data ++= context.data
    contextBuilder += ("action", action)
    contextBuilder += ("isPrivate", keep.isPrivate)
    contextBuilder += ("hasTitle", keep.title.isDefined)
    contextBuilder += ("uriId", keep.uriId.toString)
    contextBuilder += ("tagId", tag.id.get.toString)
    heimdal.trackEvent(UserEvent(tag.userId, contextBuilder.build, UserEventTypes.KEPT, changedAt))

    // Anonymized event with tag information
    if (action == "taggedPage") {
      anonymise(contextBuilder)
      contextBuilder.addUrlInfo(keep.url)
      contextBuilder += ("tagName", tag.name)
      heimdal.trackEvent(AnonymousEvent(contextBuilder.build, AnonymousEventTypes.KEPT, changedAt))
    }
  }

  private def anonymise(contextBuilder: HeimdalContextBuilder): Unit = contextBuilder.anonymise("uriId", "tagId")
}
