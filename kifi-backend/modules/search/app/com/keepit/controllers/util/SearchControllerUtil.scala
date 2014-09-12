package com.keepit.controllers.util

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.db.Id
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.search.engine.result.KifiPlainResult
import com.keepit.search.result.{ ResultUtil, DecoratedResult, KifiSearchResult }
import com.keepit.search.util.IdFilterCompressor
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{ JsValue, Json, JsObject }

import scala.concurrent.Future
import com.keepit.search._
import com.keepit.search.result.DecoratedResult
import play.api.libs.json.JsObject
import com.keepit.common.akka.SafeFuture

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

  def augment(augmentationCommander: AugmentationCommander)(userId: Id[User], kifiPlainResult: KifiPlainResult): Future[JsValue] = {
    val items = kifiPlainResult.hits.map { hit => AugmentableItem(Id(hit.id), hit.libraryId.map(Id(_))) }
    val previousItems = (kifiPlainResult.idFilter.map(Id[NormalizedURI](_)) -- items.map(_.uri)).map(AugmentableItem(_, None))
    val context = AugmentationContext.uniform(userId, items ++ previousItems)
    val augmentationRequest = ItemAugmentationRequest(items.toSet, context)
    augmentationCommander.augmentation(augmentationRequest).map { augmentationResponse =>
      val augmenter = AugmentedItem.withScores(augmentationResponse.scores) _
      val augmentedItems = items.map(item => augmenter(item, augmentationResponse.infos(item)))
      Json.toJson(augmentedItems)
    }
  }
}
