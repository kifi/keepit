package com.keepit.search

import com.keepit.common.logging.Logging
import com.keepit.search.graph.collection.CollectionSearcherWithUser
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Await
import com.keepit.search.index.WrappedIndexReader
import com.keepit.search.semantic._

object PersonalizedSearcher {
  def apply(indexReader: WrappedIndexReader,
    myUris: Set[Long],
    collectionSearcher: CollectionSearcherWithUser = null,
    nonPersonalizedContextVectorFuture: Option[Future[SemanticVector]] = None,
    useNonPersonalizedContextVector: Boolean = false) = {

    new PersonalizedSearcher(
      indexReader,
      myUris,
      collectionSearcher,
      nonPersonalizedContextVectorFuture,
      useNonPersonalizedContextVector)
  }
}

class PersonalizedSearcher(
  override val indexReader: WrappedIndexReader,
  myUris: Set[Long],
  val collectionSearcher: CollectionSearcherWithUser,
  nonPersonalizedContextVectorFuture: Option[Future[SemanticVector]] = None,
  useNonPersonalizedContextVector: Boolean = false)
    extends Searcher(indexReader) with SearchSemanticContext with Logging {

  override def getContextVector: SemanticVector = {
    if (useNonPersonalizedContextVector) {
      Await.result(nonPersonalizedContextVectorFuture.get, 1 second)
    } else super.getContextVector
  }

  override protected def getSemanticVectorComposer(term: Term) = {
    val subReaders = indexReader.wrappedSubReaders
    val composer = new SemanticVectorComposer
    var i = 0
    var numPayloads = 0
    while (i < subReaders.length) {
      val subReader = subReaders(i)
      val idMapper = subReader.getIdMapper
      val tp = subReader.termPositionsEnum(term)
      if (tp != null) {
        while (tp.nextDoc() < NO_MORE_DOCS) {
          val id = idMapper.getId(tp.docID())
          if (myUris.contains(id)) {
            var freq = tp.freq()
            if (freq > 0) {
              tp.nextPosition()
              val payload = tp.getPayload()
              if (payload != null) {
                numPayloads += 1
                composer.add(payload.bytes, payload.offset, payload.length, 1)
              } else {
                log.error(s"payload is missing: term=${term.toString}")
              }
            }
          }
        }
      }
      i += 1
    }
    numPayloadsMap += term -> numPayloads
    composer
  }

}
