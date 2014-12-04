package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import com.keepit.heimdal._
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.google.inject.{ Singleton, Inject }
import org.joda.time.DateTime
import play.api.db
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class LibraryAnalytics @Inject() (
  db: Database,
  keepRepo : KeepRepo
) (heimdal: HeimdalServiceClient) {

  def sendLibraryInvite(userId: Id[User], libId: Id[Library], inviteeList: Seq[(Either[Id[User], EmailAddress])], eventContext: HeimdalContext) = {
    val builder = new HeimdalContextBuilder
    builder.addExistingContext(eventContext)
    builder += ("action", "sent")
    builder += ("category", "libraryInvitation")
    builder += ("libraryId", libId.id.toString)
    builder += ("libraryOwnerId", userId.id.toString)
    val numUsers = inviteeList.count(_.isLeft)
    val numEmails = inviteeList.size - numUsers
    builder += ("numUserInvited", numUsers)
    builder += ("numNonUserInvited", numEmails)

    heimdal.trackEvent(UserEvent(userId, builder.build, UserEventTypes.INVITED))
  }

  def acceptLibraryInvite(userId: Id[User], libId: Id[Library], eventContext: HeimdalContext) = {
    val builder = new HeimdalContextBuilder
    builder.addExistingContext(eventContext)
    builder += ("action", "accepted")
    builder += ("category", "libraryInvitation")
    builder += ("libraryId", libId.id.toString)
    heimdal.trackEvent(UserEvent(userId, builder.build, UserEventTypes.INVITED))
  }

  def followLibrary(userId: Id[User], library: Library, keepCount: Int, context: HeimdalContext): Unit = {
    val followedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= context.data
      contextBuilder += ("action", "followed")
      contextBuilder += ("privacySetting", library.visibility.toString)
      contextBuilder += ("libraryId", library.id.toString)
      contextBuilder += ("libraryOwnerId", library.ownerId.toString)
      contextBuilder += ("followerCount", library.memberCount - 1)
      contextBuilder += ("keepCount", keepCount)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.FOLLOWED_LIBRARY, followedAt))
    }
  }

  def unfollowLibrary(userId: Id[User], library: Library, keepCount: Int, context: HeimdalContext): Unit = {
    val unfollowedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= context.data
      contextBuilder += ("action", "unfollowed")
      contextBuilder += ("privacySetting", library.visibility.toString)
      contextBuilder += ("libraryId", library.id.toString)
      contextBuilder += ("libraryOwnerId", library.ownerId.toString)
      contextBuilder += ("followerCount", library.memberCount - 1)
      contextBuilder += ("keepCount", keepCount)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.FOLLOWED_LIBRARY, unfollowedAt))
    }
  }

  def createLibrary(userId: Id[User], library: Library, context: HeimdalContext): Unit = {
    val createdLibraryAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= context.data
      contextBuilder += ("action", "created")
      contextBuilder += ("privacySetting", library.visibility.toString)
      contextBuilder += ("libraryId", library.id.toString)
      contextBuilder += ("libraryOwnerId", library.ownerId.toString)
      contextBuilder += ("description", library.description.map(_.length).getOrElse(0))
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.MODIFIED_LIBRARY, createdLibraryAt))
    }
  }
  def deleteLibrary(userId: Id[User], library: Library, context: HeimdalContext): Unit = {
    val createdLibraryAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= context.data
      contextBuilder += ("action", "deleted")
      contextBuilder += ("privacySetting", library.visibility.toString)
      contextBuilder += ("libraryId", library.id.toString)
      contextBuilder += ("libraryOwnerId", library.ownerId.toString)
      contextBuilder += ("description", library.description.map(_.length).getOrElse(0))
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.MODIFIED_LIBRARY, createdLibraryAt))
    }
  }
  def editLibrary(userId: Id[User], library: Library, context: HeimdalContext, subAction: Option[String] = None): Unit = {
    val createdLibraryAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= context.data
      contextBuilder += ("action", "edited")
      subAction match {
        case Some("move_keeps") =>
          contextBuilder += ("subAction", "movedKeeps")
        case Some("copy_keeps") =>
          contextBuilder += ("subAction", "copiedKeeps")
        case Some("import_keeps") =>
          contextBuilder += ("subAction", "importedKeeps")
        case _ =>
      }
      contextBuilder += ("privacySetting", library.visibility.toString)
      contextBuilder += ("libraryId", library.id.toString)
      contextBuilder += ("libraryOwnerId", library.ownerId.toString)
      contextBuilder += ("description", library.description.map(_.length).getOrElse(0))
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.MODIFIED_LIBRARY, createdLibraryAt))
    }
  }

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
      contextBuilder += ("oldTagName", oldTag.name.tag)
      contextBuilder += ("tagName", newTag.name.tag)
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
      contextBuilder += ("tagName", newTag.name.tag)
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

  def undeletedTag(tag: Collection, context: HeimdalContext): Unit = {
    val undeletedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= context.data
      contextBuilder += ("action", "undeletedTag")
      contextBuilder += ("tagId", tag.id.get.toString)
      heimdal.trackEvent(UserEvent(tag.userId, contextBuilder.build, UserEventTypes.KEPT, undeletedAt))
      heimdal.incrementUserProperties(tag.userId, "tags" -> 1)
    }
  }

  private def populateLibraryInfoForKeep(library: Library): HeimdalContext = {
    val numKeepsInLibrary = db.readOnlyMaster { implicit s =>
      keepRepo.getCountByLibrary(library.id.get)
    }

    val numDays = (currentDateTime.getMillis.toFloat - library.createdAt.getMillis) / (24 * 3600 * 1000)
    val contextBuilder = new HeimdalContextBuilder
    contextBuilder += ("libraryId", library.id.get.toString)
    contextBuilder += ("libraryOwnerId", library.ownerId.toString)
    contextBuilder += ("libraryKeepCount", numKeepsInLibrary)
    contextBuilder += ("daysSinceLibraryCreated", numDays)
    contextBuilder.build
  }

  def keptPages(userId: Id[User], keeps: Seq[Keep], library: Library, existingContext: HeimdalContext): Unit = SafeFuture {
    val keptAt = currentDateTime

    keeps.collect {
      case bookmark if bookmark.source != KeepSource.default =>
        val contextBuilder = new HeimdalContextBuilder
        contextBuilder.data ++= existingContext.data
        contextBuilder += ("action", "keptPage")
        contextBuilder += ("source", bookmark.source.value)
        contextBuilder += ("isPrivate", bookmark.isPrivate)
        contextBuilder += ("hasTitle", bookmark.title.isDefined)
        contextBuilder += ("uriId", bookmark.uriId.toString)
        contextBuilder ++= populateLibraryInfoForKeep(library).data

        val context = contextBuilder.build
        heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.KEPT, keptAt))
        if (!KeepSource.imports.contains(bookmark.source)) {
          heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.USED_KIFI, keptAt))
        }

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

  def keepImport(userId: Id[User], keptAt: DateTime, existingContext: HeimdalContext, countImported: Int, source: KeepSource): Unit = {
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      contextBuilder += ("action", "importedBookmarks")
      contextBuilder += ("importedBookmarks", countImported)
      contextBuilder += ("source", source.value)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.JOINED, keptAt))
    }
  }

  def unkeptPages(userId: Id[User], keeps: Seq[Keep], library: Library, context: HeimdalContext): Unit = {
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
        contextBuilder ++= populateLibraryInfoForKeep(library).data
        heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.KEPT, unkeptAt))
      }
      val unkept = keeps.length
      val unkeptPrivate = keeps.count(_.isPrivate)
      val unkeptPublic = unkept - unkeptPrivate
      heimdal.incrementUserProperties(userId, "keeps" -> -unkept, "privateKeeps" -> -unkeptPrivate, "publicKeeps" -> -unkeptPublic)
    }
  }

  def rekeptPages(userId: Id[User], keeps: Seq[Keep], context: HeimdalContext): Unit = {
    val rekeptAt = currentDateTime

    SafeFuture {
      keeps.foreach { keep =>
        val contextBuilder = new HeimdalContextBuilder
        contextBuilder.data ++= context.data
        contextBuilder += ("action", "rekeptPage")
        contextBuilder += ("keepSource", keep.source.value)
        contextBuilder += ("isPrivate", keep.isPrivate)
        contextBuilder += ("hasTitle", keep.title.isDefined)
        contextBuilder += ("uriId", keep.uriId.toString)
        heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.KEPT, rekeptAt))
      }
      val rekept = keeps.length
      val rekeptPrivate = keeps.count(_.isPrivate)
      val rekeptPublic = rekept - rekeptPrivate
      heimdal.incrementUserProperties(userId, "keeps" -> rekept, "privateKeeps" -> rekeptPrivate, "publicKeeps" -> rekeptPublic)
    }
  }

  def updatedKeep(oldKeep: Keep, updatedKeep: Keep, context: HeimdalContext): Unit = SafeFuture {
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

  def taggedPage(tag: Collection, keep: Keep, context: HeimdalContext, taggedAt: DateTime = currentDateTime): Unit = {
    val isDefaultTag = context.get[String]("source").map(_ == KeepSource.default.value) getOrElse false
    if (!isDefaultTag) changedTag(tag, keep, "taggedPage", context, taggedAt)
  }

  def untaggedPage(tag: Collection, keep: Keep, context: HeimdalContext, untaggedAt: DateTime = currentDateTime): Unit =
    changedTag(tag, keep, "untaggedPage", context, untaggedAt)

  private def changedTag(tag: Collection, keep: Keep, action: String, context: HeimdalContext, changedAt: DateTime): Unit = SafeFuture {
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
      contextBuilder += ("tagName", tag.name.tag)
      heimdal.trackEvent(AnonymousEvent(contextBuilder.build, AnonymousEventTypes.KEPT, changedAt))
    }
  }

  private def anonymise(contextBuilder: HeimdalContextBuilder): Unit = contextBuilder.anonymise("uriId", "tagId")
}
