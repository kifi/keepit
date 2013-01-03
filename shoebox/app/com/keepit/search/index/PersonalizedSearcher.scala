package com.keepit.search.index

import com.keepit.search.SemanticVectorComposer
import com.keepit.search.SemanticVector
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.SegmentReader

object PersonalizedSearcher{
  def apply(searcher: Searcher, ids: Set[Long]) = new PersonalizedSearcher(searcher.indexReader, searcher.subReaderArray, searcher.idMappers, ids)
}

class PersonalizedSearcher(override val indexReader: IndexReader, override val subReaderArray: Array[SegmentReader], override val idMappers: Map[String, IdMapper], ids: Set[Long])
extends Searcher(indexReader, subReaderArray, idMappers) {

  override protected def getSemanticVectorComposer(term: Term) = {
    val composer = new SemanticVectorComposer
    var vector = new Array[Byte](SemanticVector.arraySize)
    var i = 0
    while (i < subReaderArray.length) {
      val subReader = subReaderArray(i)
      val idMapper = idMappers(subReader.getSegmentName)
      val tp = subReader.termPositions(term)
      try {
        while (tp.next) {
          if (ids.contains(idMapper.getId(tp.doc()))) {
            var freq = tp.freq()
            while (freq > 0) {
              freq -= 1
              tp.nextPosition()
              vector = tp.getPayload(vector, 0)
              composer.add(vector)
            }
          }
        }
      } finally {
        tp.close()
      }
      i += 1
    }
    composer
  }
}