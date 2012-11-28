package com.keepit.search.index

import com.keepit.search.SemanticVector
import com.keepit.search.SemanticVectorComposer
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.Payload
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer

class Searcher(val indexReader: IndexReader, val idMapper: IdMapper) extends IndexSearcher(indexReader) {

  // search: hits are ordered by score
  def search(query: Query): Seq[Hit] = {
    val hitBuf = new ArrayBuffer[Hit]()
    doSearch(query){ scorer =>
      var doc = scorer.nextDoc()
      while (doc != NO_MORE_DOCS) {
        var score = scorer.score()
        hitBuf += Hit(idMapper.getId(doc), score)
        doc = scorer.nextDoc()
      }
    }
    hitBuf.sortWith((a, b) => a.score >= b.score).toSeq
  }

  def doSearch[R](query: Query)(f: Scorer => Unit) = {
    val rewrittenQuery = rewrite(query)
    if (rewrittenQuery != null) {
      val weight = createNormalizedWeight(rewrittenQuery)
      if(weight != null) {
        val scorer = weight.scorer(indexReader, true, true)
        if (scorer != null) {
          f(scorer)
        }
      }
    }
  }

  protected def getSemanticVectorComposer(term: Term) = {
    val composer = new SemanticVectorComposer
    val tp = indexReader.termPositions(term)
    var vector = new Array[Byte](SemanticVector.arraySize)
    try {
      while (tp.next) {
        var freq = tp.freq()
        while (freq > 0) {
          freq -= 1
          tp.nextPosition()
          vector = tp.getPayload(vector, 0)
          composer.add(vector)
        }
      }
    } finally {
      tp.close()
    }
    composer
  }

  def getSemanticVector(term: Term) = {
    val composer = getSemanticVectorComposer(term)

    if (composer.numInputs > 0) composer.getSemanticVector
    else SemanticVector.vectorize(SemanticVector.getSeed(term.text))
  }

  def getSemanticVector(terms: Set[Term]) = {
    val sketch = terms.foldLeft(SemanticVector.emptySketch){ (sketch, term) =>
      val composer = getSemanticVectorComposer(term)
      val termSketch = if (composer.numInputs > 0) composer.getQuasiSketch else SemanticVector.getSeed(term.text)
      SemanticVector.updateSketch(sketch, termSketch)
      sketch
    }
    SemanticVector.vectorize(sketch)
  }

  def getHitQueue(size: Int) = new HitQueue(size)
}

class MutableHit(var id: Long, var score: Float) {
  def apply(newId: Long, newScore: Float) = {
    id = newId
    score = newScore
  }
}

class HitQueue(sz: Int) extends PriorityQueue[MutableHit] {
  initialize(sz)
  override def lessThan(a: MutableHit, b: MutableHit) = (a.score < b.score || (a.score == b.score && a.id < b.id))

  var overflow: MutableHit = null // sorry about the null, but this is necessary to work with lucene's priority queue efficiently

  def insert(id: Long, score: Float) {
    if (overflow == null) overflow = new MutableHit(id, score)
    else overflow(id, score)

    overflow = insertWithOverflow(overflow)
  }

  // the following method is destructive. after the call HitQueue is unusable
  def toList: List[MutableHit] = {
    var res: List[MutableHit] = Nil
    var i = size()
    while (i > 0) {
      i -= 1
      res = pop() :: res
    }
    res
  }
}

case class Hit(id: Long, score: Float)

