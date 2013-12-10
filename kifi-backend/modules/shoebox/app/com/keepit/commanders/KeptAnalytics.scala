package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.model.{KeepToCollection, Bookmark, Collection, User}
import com.keepit.heimdal._
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.google.inject.{Singleton, Inject}
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class KeptAnalytics @Inject() (heimdal : HeimdalServiceClient) {
  def renamedTag(oldTag: Collection, newTag: Collection, context: HeimdalContext): Unit = {
    val renamedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++ context.data
      contextBuilder += ("action", "renamedTag")
      contextBuilder += ("oldName", oldTag.name)
      contextBuilder += ("newName", newTag.name)
      heimdal.trackEvent(UserEvent(oldTag.userId.id, contextBuilder.build, UserEventTypes.KEPT, renamedAt))
    }
  }

  def createdTag(newTag: Collection, context: HeimdalContext): Unit = {
    val createdAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++ context.data
      contextBuilder += ("action", "createdTag")
      contextBuilder += ("name", newTag.name)
      heimdal.trackEvent(UserEvent(newTag.userId.id, contextBuilder.build, UserEventTypes.KEPT, createdAt))
      heimdal.incrementUserProperties(newTag.userId, "tags" -> 1)
    }
  }

  def deletedTag(oldTag: Collection, context: HeimdalContext): Unit = {
    val deletedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++ context.data
      contextBuilder += ("action", "deletedTag")
      contextBuilder += ("name", oldTag.name)
      heimdal.trackEvent(UserEvent(oldTag.userId.id, contextBuilder.build, UserEventTypes.KEPT, deletedAt))
      heimdal.incrementUserProperties(oldTag.userId, "tags" -> -1)
    }
  }

  def keptPages(userId: Id[User], keeps: Seq[Bookmark], context: HeimdalContext): Unit = {
    val keptAt = currentDateTime

    SafeFuture {
      keeps.foreach { bookmark =>
        val contextBuilder = new HeimdalContextBuilder
        contextBuilder.data ++ context.data
        contextBuilder += ("action", "keptPage")
        contextBuilder += ("source", bookmark.source.value)
        contextBuilder += ("isPrivate", bookmark.isPrivate)
        contextBuilder += ("url", bookmark.url)
        contextBuilder += ("hasTitle", bookmark.title.isDefined)
        heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.KEPT, keptAt))
      }
      val kept = keeps.length
      val keptPrivate = keeps.count(_.isPrivate)
      val keptPublic = kept - keptPrivate
      heimdal.incrementUserProperties(userId, "keeps" -> kept, "privateKeeps" -> keptPrivate, "publicKeeps" -> keptPublic)
    }
  }

  def unkeptPages(userId: Id[User], keeps: Seq[Bookmark], context: HeimdalContext): Unit = {
    val unkeptAt = currentDateTime

    SafeFuture {
      keeps.foreach { keep =>
        val contextBuilder = new HeimdalContextBuilder
        contextBuilder.data ++ context.data
        contextBuilder += ("action", "unkeptPage")
        contextBuilder += ("isPrivate", keep.isPrivate)
        contextBuilder += ("url", keep.url)
        contextBuilder += ("hasTitle", keep.title.isDefined)
        heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.KEPT, unkeptAt))
      }
      val unkept = keeps.length
      val unkeptPrivate = keeps.count(_.isPrivate)
      val unkeptPublic = unkept - unkeptPrivate
      heimdal.incrementUserProperties(userId, "keeps" -> - unkept, "privateKeeps" -> - unkeptPrivate, "publicKeeps" -> - unkeptPublic)
    }
  }

  def updatedPrivacy(updatedKeep: Bookmark, context: HeimdalContext): Unit = SafeFuture {
    val contextBuilder = new HeimdalContextBuilder
    contextBuilder.data ++ context.data
    val action = if (updatedKeep.isPrivate) "madeKeepPrivate" else "madeKeepPublic"
    contextBuilder += ("action", action)
    contextBuilder += ("url", updatedKeep.url)
    heimdal.trackEvent(UserEvent(updatedKeep.userId.id, contextBuilder.build, UserEventTypes.KEPT, updatedKeep.updatedAt))

    val madePrivate = if (updatedKeep.isPrivate) 1 else -1
    val madePublic = - madePrivate
    heimdal.incrementUserProperties(updatedKeep.userId, "privateKeeps" -> madePrivate, "publicKeeps" -> madePublic)
}

  def taggedPage(tag: Collection, keep: Bookmark, context: HeimdalContext, time: DateTime = currentDateTime): Unit = {}
  def untaggedPage(tag: Collection, keep: Bookmark, context: HeimdalContext, time: DateTime = currentDateTime): Unit = {}
}
