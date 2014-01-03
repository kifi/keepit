package com.keepit.commanders

import com.google.inject.Inject

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.common.logging.Logging
import com.keepit.common.akka.SafeFuture
import com.keepit.search.SearchServiceClient
import com.keepit.heimdal._
import com.keepit.common.social.BasicUserRepo

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.KestrelCombinator

case class KeepInfo(id: Option[ExternalId[Bookmark]] = None, title: Option[String], url: String, isPrivate: Boolean)

case class FullKeepInfo(bookmark: Bookmark, users: Set[BasicUser], collections: Set[ExternalId[Collection]], others: Int)

class FullKeepInfoWriter extends Writes[FullKeepInfo] {
  def writes(info: FullKeepInfo) = Json.obj(
    "id" -> info.bookmark.externalId.id,
    "title" -> info.bookmark.title,
    "url" -> info.bookmark.url,
    "isPrivate" -> info.bookmark.isPrivate,
    "createdAt" -> info.bookmark.createdAt,
    "others" -> info.others,
    "keepers" -> info.users,
    "collections" -> info.collections.map(_.id)
  )
}

object KeepInfo {
  implicit val format = (
    (__ \ 'id).formatNullable(ExternalId.format[Bookmark]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'isPrivate).formatNullable[Boolean].inmap[Boolean](_ getOrElse false, Some(_))
  )(KeepInfo.apply _, unlift(KeepInfo.unapply))

  def fromBookmark(bookmark: Bookmark): KeepInfo = {
    KeepInfo(Some(bookmark.externalId), bookmark.title, bookmark.url, bookmark.isPrivate)
  }
}

case class KeepInfosWithCollection(
  collection: Option[Either[ExternalId[Collection], String]], keeps: Seq[KeepInfo])

object KeepInfosWithCollection {
  implicit val reads = (
    (__ \ 'collectionId).read(ExternalId.format[Collection])
      .map[Option[Either[ExternalId[Collection], String]]](c => Some(Left(c)))
    orElse (__ \ 'collectionName).readNullable[String]
      .map(_.map[Either[ExternalId[Collection], String]](Right(_))) and
    (__ \ 'keeps).read[Seq[KeepInfo]]
  )(KeepInfosWithCollection.apply _)
}

class BookmarksCommander @Inject() (
    db: Database,
    bookmarkInterner: BookmarkInterner,
    searchClient: SearchServiceClient,
    keepToCollectionRepo: KeepToCollectionRepo,
    basicUserRepo: BasicUserRepo,
    uriRepo: NormalizedURIRepo,
    bookmarkRepo: BookmarkRepo,
    collectionRepo: CollectionRepo,
    collectionCommander: CollectionCommander,
    keptAnalytics: KeepingAnalytics,
    rawBookmarkFactory: RawBookmarkFactory
 ) extends Logging {

  def allKeeps(before: Option[ExternalId[Bookmark]], after: Option[ExternalId[Bookmark]], collectionId: Option[ExternalId[Collection]], count: Int, userId: Id[User]): Future[(Option[BasicCollection], Seq[FullKeepInfo])] = {
    val (keeps, collectionOpt) = db.readOnly { implicit s =>
      val collectionOpt = (collectionId map { id => collectionRepo.getByUserAndExternalId(userId, id)}).flatten
      val keeps = bookmarkRepo.getByUser(userId, before, after, collectionOpt map (_.id.get), count)
      (keeps, collectionOpt)
    }
    searchClient.sharingUserInfo(userId, keeps.map(_.uriId)) map { infos =>
      log.info(s"got sharingUserInfo: $infos")
      db.readOnly { implicit s =>
        val idToBasicUser = infos.flatMap(_.sharingUserIds).distinct.map(id => id -> basicUserRepo.load(id)).toMap
        val collIdToExternalId = collectionRepo.getByUser(userId).map(c => c.id.get -> c.externalId).toMap
        (keeps zip infos).map { case (keep, info) =>
          val collIds =
            keepToCollectionRepo.getCollectionsForBookmark(keep.id.get).flatMap(collIdToExternalId.get).toSet
          val others = info.keepersEdgeSetSize - info.sharingUserIds.size - (if (keep.isPrivate) 0 else 1)
          FullKeepInfo(keep, info.sharingUserIds map idToBasicUser, collIds, others)
        }
      }
    } map { keepsInfo =>
      (collectionOpt.map(BasicCollection fromCollection _), keepsInfo)
    }
  }

  def keepMultiple(keepInfosWithCollection: KeepInfosWithCollection, userId: Id[User], source: BookmarkSource)(implicit context: HeimdalContext):
                  (Seq[KeepInfo], Option[Int]) = {
    val KeepInfosWithCollection(collection, keepInfos) = keepInfosWithCollection
    val keeps = bookmarkInterner.internRawBookmarks(rawBookmarkFactory.toRawBookmark(keepInfos), userId, source, true)

    val addedToCollection = collection flatMap {
      case Left(collectionId) => db.readOnly { implicit s => collectionRepo.getOpt(collectionId) }
      case Right(name) => Some(getOrCreateTag(userId, name))
    } map { coll =>
      addToCollection(coll, keeps).size
    }
    SafeFuture{
      searchClient.updateURIGraph()
    }
    (keeps.map(KeepInfo.fromBookmark), addedToCollection)
  }

  def unkeepMultiple(keepInfos: Seq[KeepInfo], userId: Id[User])(implicit context: HeimdalContext): Seq[KeepInfo] = {

    val deactivatedBookmarks = db.readWrite { implicit s =>
      keepInfos.map { ki =>
        val url = ki.url
        uriRepo.getByUri(url).flatMap { uri =>
          bookmarkRepo.getByUriAndUser(uri.id.get, userId).map { b =>
            bookmarkRepo.save(b withActive false)
          }
        }
      }
    }.flatten

    val deactivatedKeepInfos = deactivatedBookmarks.map(KeepInfo.fromBookmark(_))
    keptAnalytics.unkeptPages(userId, deactivatedBookmarks, context)
    searchClient.updateURIGraph()
    deactivatedKeepInfos
  }

  def updateKeep(keep: Bookmark, isPrivate: Option[Boolean], title: Option[String])(implicit context: HeimdalContext): Option[Bookmark] = {
    val shouldBeUpdated = (isPrivate.isDefined && keep.isPrivate != isPrivate.get) || (title.isDefined && keep.title != title)
    if (shouldBeUpdated) Some {
      val updatedPrivacy = isPrivate getOrElse keep.isPrivate
      val updatedTitle = title orElse keep.title
      val updatedKeep = db.readWrite { implicit s => bookmarkRepo.save(keep.withPrivate(updatedPrivacy).withTitle(updatedTitle)) }
      searchClient.updateURIGraph()
      keptAnalytics.updatedKeep(keep, updatedKeep, context)
      updatedKeep
    } else None
  }

  def addToCollection(collection: Collection, keeps: Seq[Bookmark])(implicit context: HeimdalContext): Set[KeepToCollection] = {
    db.readWrite(attempts = 2) { implicit s =>
      val keepsById = keeps.map(keep => keep.id.get -> keep).toMap
      val existing = keepToCollectionRepo.getByCollection(collection.id.get, excludeState = None).toSet
      val created = (keepsById.keySet -- existing.map(_.bookmarkId)) map { bid =>
        keepToCollectionRepo.save(KeepToCollection(bookmarkId = bid, collectionId = collection.id.get))
      }
      val activated = existing collect {
        case ktc if ktc.state == KeepToCollectionStates.INACTIVE && keepsById.contains(ktc.bookmarkId) =>
          keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE))
      }

      searchClient.updateURIGraph()
      val tagged = (activated ++ created).toSet
      val taggingAt = currentDateTime
      tagged.foreach(ktc => keptAnalytics.taggedPage(collection, keepsById(ktc.bookmarkId), context, taggingAt))
      tagged
    }
  }

  def removeFromCollection(collection: Collection, keeps: Seq[Bookmark])(implicit context: HeimdalContext): Set[KeepToCollection] = {
    db.readWrite(attempts = 2) { implicit s =>
      val keepsById = keeps.map(keep => keep.id.get -> keep).toMap
      val removed = keepToCollectionRepo.getByCollection(collection.id.get, excludeState = None) collect {
        case ktc if ktc.state != KeepToCollectionStates.INACTIVE && keepsById.contains(ktc.bookmarkId) =>
          keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
      }
      val removedAt = currentDateTime
      removed.foreach(ktc => keptAnalytics.untaggedPage(collection, keepsById(ktc.bookmarkId), context, removedAt))
      searchClient.updateURIGraph()
      removed.toSet
    }
  }

  def tagUrl(tag: Collection, json: JsValue, userId: Id[User], source: BookmarkSource, kifiInstallationId: Option[ExternalId[KifiInstallation]])(implicit context: HeimdalContext) = {
    val bookmark = bookmarkInterner.internRawBookmarks(rawBookmarkFactory.toRawBookmark(json), userId, source, mutatePrivacy = false, installationId = kifiInstallationId)
    addToCollection(tag, bookmark)
  }

  def getOrCreateTag(userId: Id[User], name: String)(implicit context: HeimdalContext): Collection = {
    val normalizedName = name.trim.replaceAll("""\s+""", " ").take(Collection.MaxNameLength)
    val collection = db.readOnly { implicit s =>
      collectionRepo.getByUserAndName(userId, normalizedName, excludeState = None)
    }
    collection match {
      case Some(t) if t.isActive => t
      case Some(t) => db.readWrite { implicit s => collectionRepo.save(t.copy(state = CollectionStates.ACTIVE)) } tap(keptAnalytics.createdTag(_, context))
      case None => db.readWrite { implicit s => collectionRepo.save(Collection(userId = userId, name = normalizedName)) } tap(keptAnalytics.createdTag(_, context))
    }
  }

  def removeTag(id: ExternalId[Collection], url: String, userId: Id[User])(implicit context: HeimdalContext): Unit = {
    db.readWrite { implicit s =>
      for {
        uri <- uriRepo.getByUri(url)
        bookmark <- bookmarkRepo.getByUriAndUser(uri.id.get, userId)
        collection <- collectionRepo.getOpt(id)
      } {
        keepToCollectionRepo.remove(bookmarkId = bookmark.id.get, collectionId = collection.id.get)
        keptAnalytics.untaggedPage(collection, bookmark, context)
      }
    }
  }


  def keepWithMultipleTags(userId: Id[User], keepsWithTags: Seq[(KeepInfo, Seq[String])], source: BookmarkSource)(implicit context: HeimdalContext): Map[Collection, Seq[Bookmark]] = {
    val keepsByUrl = bookmarkInterner.internRawBookmarks(
      rawBookmarkFactory.toRawBookmark(keepsWithTags.map(_._1)),
      userId,
      mutatePrivacy = true,
      installationId = None,
      source = BookmarkSource.default
    ).map(keep => keep.url -> keep).toMap

    val keepsByTagName = keepsWithTags.flatMap { case (keepInfo, tags) =>
      tags.map(tagName => (tagName, keepsByUrl(keepInfo.url)))
    }.groupBy(_._1).mapValues(_.map(_._2))

    val keepsByTag = keepsByTagName.map { case (tagName, keeps) =>
      val tag = getOrCreateTag(userId, tagName)
      addToCollection(tag, keeps)
      tag -> keeps
    }.toMap

    keepsByTag
  }

  def setKeepOrdering(userId: Id[User], keeps: Seq[Bookmark], inBetween: Option[(Bookmark, Bookmark)] = None): Unit = {
    require(keeps.forall(_.userId == userId), "Keeps to be moved do not belong to the expected user.")
    inBetween match {
      case Some((firstKeep, secondKeep)) =>
        val Seq(lowerKeep, upperKeep) = Seq(firstKeep, secondKeep).sortBy(_.createdAt)
        val interval = upperKeep.createdAt.getMillis - lowerKeep.createdAt.getMillis
        val step = interval.toInt / (keeps.length + 1)
        require(step > 0, "Cannot insert in between keeps that have been created at the same time.")
        db.readWrite { implicit session =>
          keeps.reverse.zipWithIndex.foreach { case (keep, i) =>
            bookmarkRepo.save(keep.copy(createdAt = lowerKeep.createdAt.plusMillis((i+1) * step)))
          }
        }

      case None => db.readWrite { implicit session =>
        keeps.reverse.zipWithIndex.foreach { case (keep, i) =>
          bookmarkRepo.save(keep.copy(createdAt = currentDateTime))
        }
      }
    }
  }

  def createDefaultKeeps(userId: Id[User])(implicit context: HeimdalContext): Unit = {
    val contextBuilder = new HeimdalContextBuilder()
    contextBuilder.data ++= context.data
    contextBuilder += ("source", BookmarkSource.default.value) // manually set the source so that it appears in tag analytics
    val keepsByTag = keepWithMultipleTags(userId, DefaultKeeps.orderedKeepsWithTags, BookmarkSource.default)(contextBuilder.build)
    val tagsByName = keepsByTag.keySet.map(tag => tag.name -> tag).toMap
    val keepsByUrl = keepsByTag.values.flatten.map(keep => keep.url -> keep).toMap
    db.readWrite { implicit session => collectionCommander.setCollectionOrdering(userId, DefaultKeeps.orderedTags.map(tagsByName(_).externalId)) }
    setKeepOrdering(userId, DefaultKeeps.orderedKeepsWithTags.map { case (keepInfo, _) => keepsByUrl(keepInfo.url) })
  }
}

object DefaultKeeps {
  val orderedTags: Seq[String] = Seq(
    "Recipe",
    "Shopping Wishlist",
    "Travel",
    "Read Later",
    "Funny",
    "Example Keep",
    "Kifi Support"
  )

  val orderedKeepsWithTags: Seq[(KeepInfo, Seq[String])] = {
    val Seq(recipe, shopping, travel, later, funny, example, support) = orderedTags
    Seq(
      // Support Keeps
      (KeepInfo(title = Some("Install Kifi"), url = "https://www.kifi.com/install", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("How to Use Kifi"), url = "https://www.kifi.com/support", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("Contact Us"), url = "", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("Kifi is better with more friends"), url = "https://www.kifi.com/friends/invite", isPrivate = true), Seq(support)),

      // Example keeps
      (KeepInfo(title = None, url = "http://www.simplyrecipes.com/recipes/bruschetta_with_tomato_and_basil/", isPrivate = true), Seq(example, recipe)),
      (KeepInfo(title = None, url = "http://www.apple.com/ipad/", isPrivate = true), Seq(example, shopping)),
      (KeepInfo(title = None, url = "http://www.fourseasons.com/borabora/", isPrivate = true), Seq(example, travel)),
      (KeepInfo(title = None, url = "http://twistedsifter.com/2013/01/50-life-hacks-to-simplify-your-world/", isPrivate = true), Seq(example, later)),
      (KeepInfo(title = None, url = "http://www.youtube.com/watch?v=_OBlgSz8sSM", isPrivate = true), Seq(example, funny))
    )
  }
}


