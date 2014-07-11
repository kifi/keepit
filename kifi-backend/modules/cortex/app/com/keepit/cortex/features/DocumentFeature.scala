package com.keepit.cortex.features

import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.cortex.core.FloatVecFeature
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.nlp.Stopwords
import scala.collection.mutable

case class Document(tokens: Seq[String])

trait DocRepresenter[M <: StatModel, +FT <: FeatureRepresentation[Document, M]] extends FeatureRepresenter[Document, M, FT]

abstract class NaiveSumDocRepresenter[M <: StatModel](
    wordRep: WordRepresenter[M, FeatureRepresentation[String, M]], stopwords: Option[Stopwords] = None) extends DocRepresenter[M, FeatureRepresentation[Document, M]] {

  override val version = wordRep.version
  override val dimension = wordRep.dimension
  protected val minValidTerms = 5

  protected def normalize(vec: Array[Float]): Array[Float]

  private def wordCounts(doc: Document): Map[String, Int] = {
    val m = mutable.Map[String, Int]()
    doc.tokens.foreach { t =>
      if (stopwords.isEmpty || !stopwords.get.contains(t)) {
        m(t) = m.getOrElse(t, 0) + 1
      }
    }
    m.toMap
  }

  override def apply(doc: Document): Option[FeatureRepresentation[Document, M]] = {
    val wordCount = wordCounts(doc)
    val rep = new Array[Float](dimension)
    var validCount = 0
    for ((w, n) <- wordCount) {
      val vecOpt = wordRep.getRawVector(w)
      vecOpt.map { vec =>
        validCount += 1
        var i = 0
        while (i < dimension) {
          rep(i) += n * vec(i)
          i += 1
        }
      }
    }
    if (validCount < minValidTerms) None
    else Some(FloatVecFeature[Document, M](normalize(rep)))
  }
}
