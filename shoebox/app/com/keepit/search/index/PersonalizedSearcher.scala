package com.keepit.search.index

import com.keepit.search.SemanticVectorComposer
import com.keepit.search.SemanticVector
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term

class PersonalizedSearcher(override val indexReader: IndexReader, override val idMapper: IdMapper, ids: Set[Long]) extends Searcher(indexReader, idMapper) {

  override protected def getSemanticVectorComposer(term: Term) = {
    val composer = new SemanticVectorComposer
    val tp = indexReader.termPositions(term)
    var vector = new Array[Byte](SemanticVector.arraySize)
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
    composer
  }
}