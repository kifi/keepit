package com.keepit.cortex.core

import scala.collection.mutable

trait WordRepresenter[M <: StatModel] extends FeatureRepresenter[String, M]

abstract class HashMapWordRepresenter[M <: StatModel](
  mapper: Map[String, Array[Float]]
) extends WordRepresenter[M]{

  override def apply(word: String): Option[FeatureRepresentation[String, M]] = mapper.get(word).map{ FloatVecFeature[String, M](_) }

  override def getRawVector(word: String): Option[Array[Float]] = mapper.get(word)
}

case class Document(tokens: Seq[String])

trait DocRepresenter[M <: StatModel] extends FeatureRepresenter[Document, M]

abstract class NaiveSumDocRepresenter[M <: StatModel](
  wordRep: WordRepresenter[M]
) extends DocRepresenter[M]{

  override val version = wordRep.version
  override val dimension = wordRep.dimension
  protected val minValidTerms = 5

  protected def normalize(vec: Array[Float]): Array[Float]

  private def wordCounts(doc: Document): Map[String, Int] = {
    val m = mutable.Map[String, Int]()
    doc.tokens.foreach{ t =>
      m(t) = m.getOrElse(t, 0) + 1
    }
    m.toMap
  }

  override def apply(doc: Document): Option[FeatureRepresentation[Document, M]] = {
    val wordCount = wordCounts(doc)
    val rep = new Array[Float](dimension)
    var validCount = 0
    for ((w, n) <- wordCount){
      val vecOpt = wordRep.getRawVector(w)
      vecOpt.map{ vec =>
        validCount += 1
        var i = 0
        while (i < dimension){
          rep(i) += n * vec(i)
          i += 1
        }
      }
    }
    if (validCount < minValidTerms) None
    else Some(FloatVecFeature[Document, M](normalize(rep)))
  }
}
