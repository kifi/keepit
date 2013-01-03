package com.keepit.search.index

import com.keepit.search.SemanticVector
import com.keepit.search.SemanticVectorComposer
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.Payload
import org.apache.lucene.index.SegmentReader
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer


object Searcher {
  def apply(indexReader: IndexReader) = doOpen(indexReader, Map.empty[String, IdMapper])

  def reopen(oldSearcher: Searcher) = {
    val indexReader = IndexReader.openIfChanged(oldSearcher.indexReader)
    if (indexReader != null) {
      doOpen(indexReader, oldSearcher.idMappers)
    } else {
      oldSearcher
    }
  }

  private def doOpen(indexReader: IndexReader, oldIdMappers: Map[String, IdMapper]) = {
    var idMappers = Map.empty[String, IdMapper]
    val subReaders = indexReader.getSequentialSubReaders
    var i = 0
    while (i < subReaders.length) {
      subReaders(i) match {
        case segmentReader: SegmentReader =>
          val segmentName = segmentReader.getSegmentName
          idMappers += (segmentName -> oldIdMappers.getOrElse(segmentName, ArrayIdMapper(segmentReader)))
        case subReader => throw new IllegalStateException("not insance of %s but %s".format(classOf[SegmentReader].getName(), subReader.getClass.getName))
      }
      i += 1
    }
    new Searcher(indexReader, subReaders.map(_.asInstanceOf[SegmentReader]).toArray, idMappers)
  }
}

class Searcher(val indexReader: IndexReader, val subReaderArray: Array[SegmentReader], val idMappers: Map[String, IdMapper]) extends IndexSearcher(indexReader) {

  def idf(term: Term) = getSimilarity.idf(docFreq(term), maxDoc)

  val globalIdMapper = new IdMapper{
    def getId(docid: Int): Long = {
      var base = 0
      var i = 0
      while (i < subReaderArray.length) {
        val subReader = subReaderArray(i)
        val nextBase = base + subReader.maxDoc
        if (docid < nextBase) return idMappers(subReader.getSegmentName).getId(docid - base)
        base = nextBase
        i += 1
      }
      throw new IllegalStateException("failed to find docid: %d".format(docid))
    }

    def getDocId(id: Long): Int = {
      var base = 0
      var i = 0
      while (i < subReaderArray.length) {
        val subReader = subReaderArray(i)
        val nextBase = base + subReader.maxDoc
        val docid = idMappers(subReader.getSegmentName).getDocId(id)
        if (docid >= 0 && !subReader.isDeleted(docid)) return docid + base
        base = nextBase
        i += 1
      }
      -1
    }
  }

  // search: hits are ordered by score
  def search(query: Query): Seq[Hit] = {
    val hitBuf = new ArrayBuffer[Hit]()
    doSearch(query){ (scorer, idMapper) =>
      var doc = scorer.nextDoc()
      while (doc != NO_MORE_DOCS) {
        var score = scorer.score()
        hitBuf += Hit(idMapper.getId(doc), score)
        doc = scorer.nextDoc()
      }
    }
    hitBuf.sortWith((a, b) => a.score >= b.score).toSeq
  }

  def doSearch[R](query: Query)(f: (Scorer, IdMapper) => Unit) = {
    val rewrittenQuery = rewrite(query)
    if (rewrittenQuery != null) {
      val weight = createNormalizedWeight(rewrittenQuery)
      if(weight != null) {
        subReaderArray.foreach{ subReader =>
          val scorer = weight.scorer(subReader, true, true)
          if (scorer != null) {
            f(scorer, idMappers(subReader.getSegmentName))
          }
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

