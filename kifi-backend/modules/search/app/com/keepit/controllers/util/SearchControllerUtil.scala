package com.keepit.controllers.util

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.model.{ Library, User, NormalizedURI }
import com.keepit.search.engine.result.KifiPlainResult
import com.keepit.search.result.{ ResultUtil, KifiSearchResult }
import com.keepit.search.util.IdFilterCompressor
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import play.api.mvc.RequestHeader

import scala.concurrent.Future
import com.keepit.search._
import com.keepit.common.akka.SafeFuture
import com.keepit.search.result.DecoratedResult
import play.api.libs.json.JsObject
import com.keepit.search.graph.library.{ LibraryRecord, LibraryFields }

object SearchControllerUtil {
  val nonUser = Id[User](-1L)
}

trait SearchControllerUtil {

  @inline def safelyFlatten[E](eventuallyEnum: Future[Enumerator[E]]): Enumerator[E] = Enumerator.flatten(new SafeFuture(eventuallyEnum))

  @inline
  def reactiveEnumerator(futureSeq: Seq[Future[String]]) = {
    // Returns successful results of Futures in the order they are completed, reactively
    Enumerator.interleave(futureSeq.map { future =>
      safelyFlatten(future.map(str => Enumerator(", " + str))(immediate))
    })
  }

  def uriSummaryInfoFuture(shoeboxClient: ShoeboxServiceClient, plainResultFuture: Future[KifiPlainResult]): Future[String] = {
    plainResultFuture.flatMap { r =>
      val uriIds = r.hits.map(h => Id[NormalizedURI](h.id))
      if (uriIds.nonEmpty) {
        shoeboxClient.getUriSummaries(uriIds).map { uriSummaries =>
          KifiSearchResult.uriSummaryInfoV2(uriIds.map { uriId => uriSummaries.get(uriId) }).toString
        }
      } else {
        Future.successful(KifiSearchResult.uriSummaryInfoV2(Seq()).toString)
      }
    }
  }

  def toKifiSearchResultV2(kifiPlainResult: KifiPlainResult): JsObject = {
    KifiSearchResult.v2(
      kifiPlainResult.uuid,
      kifiPlainResult.query,
      kifiPlainResult.hits,
      kifiPlainResult.myTotal,
      kifiPlainResult.friendsTotal,
      kifiPlainResult.mayHaveMoreHits,
      kifiPlainResult.show,
      kifiPlainResult.cutPoint,
      kifiPlainResult.searchExperimentId,
      IdFilterCompressor.fromSetToBase64(kifiPlainResult.idFilter)).json
  }

  def toKifiSearchResultV1(decoratedResult: DecoratedResult): JsObject = {
    KifiSearchResult.v1(
      decoratedResult.uuid,
      decoratedResult.query,
      ResultUtil.toKifiSearchHits(decoratedResult.hits),
      decoratedResult.myTotal,
      decoratedResult.friendsTotal,
      decoratedResult.othersTotal,
      decoratedResult.mayHaveMoreHits,
      decoratedResult.show,
      decoratedResult.searchExperimentId,
      IdFilterCompressor.fromSetToBase64(decoratedResult.idFilter),
      Nil,
      decoratedResult.experts).json
  }

  def augment(augmentationCommander: AugmentationCommander, librarySearcher: Searcher, shoebox: ShoeboxServiceClient)(userId: Id[User], kifiPlainResult: KifiPlainResult): Future[JsValue] = {
    val items = kifiPlainResult.hits.map { hit => AugmentableItem(Id(hit.id), hit.libraryId.map(Id(_))) }
    val previousItems = (kifiPlainResult.idFilter.map(Id[NormalizedURI](_)) -- items.map(_.uri)).map(AugmentableItem(_, None)).toSet
    val context = AugmentationContext.uniform(userId, previousItems ++ items)
    val augmentationRequest = ItemAugmentationRequest(items.toSet, context)
    augmentationCommander.augmentation(augmentationRequest).flatMap { augmentationResponse =>
      val futureBasicUsers = shoebox.getBasicUsers(augmentationResponse.infos.values.flatMap(_.keeps.map(_.keptBy).flatten).toSeq)
      val libraryNames = getLibraryNames(librarySearcher, augmentationResponse.infos.values.flatMap(_.keeps.map(_.keptIn).flatten).toSeq)
      val augmenter = AugmentedItem.withScores(augmentationResponse.scores) _
      val augmentedItems = items.map(item => augmenter(item, augmentationResponse.infos(item)))
      futureBasicUsers.map { basicUsers =>
        val userNames = basicUsers.mapValues(basicUser => basicUser.firstName + " " + basicUser.lastName)
        JsArray(augmentedItems.map {
          augmentedItem =>
            Json.obj(
              "keep" -> augmentedItem.keep.map { case (keptIn, keptBy, tags) => Json.obj("keptIn" -> libraryNames(keptIn), "keptBy" -> keptBy.map(userNames(_)), "tags" -> tags) },
              "moreKeeps" -> JsArray(augmentedItem.moreKeeps.map { case (keptIn, keptBy) => Json.obj("keptIn" -> keptIn.map(libraryNames(_)), "keptBy" -> keptBy.map(userNames(_))) }),
              "moreTags" -> Json.toJson(augmentedItem.moreTags),
              "otherPublishedKeeps" -> augmentedItem.otherPublishedKeeps
            )
        })
      }
    }
  }

  def getLibraryNames(librarySearcher: Searcher, libraryIds: Seq[Id[Library]]): Map[Id[Library], String] = {
    libraryIds.map { libId =>
      libId -> librarySearcher.getDecodedDocValue(LibraryFields.recordField, libId.id)(LibraryRecord.fromByteArray).get.name
    }.toMap
  }

  def getLibraryContext(library: Option[String], auth: Option[String], requestHeader: RequestHeader)(implicit publicIdConfig: PublicIdConfiguration): LibraryContext = {
    val cookie = requestHeader.session.get("tbd")
    library match {
      case Some(libPublicId) =>
        val libId = Library.decodePublicId(PublicId[Library](libPublicId)).get.id
        (auth, cookie) match {
          case (Some(auth), Some(cookie)) =>
            LibraryContext.NotAuthorized(libId) // TODO: call shoebox to check permission
          case _ =>
            LibraryContext.NotAuthorized(libId)
        }
      case _ =>
        LibraryContext.None
    }
  }
}
