package com.keepit.scraper

import com.keepit.model.{NormalizedURI, ScrapeInfo}
import play.api.Play.current
import javax.xml.bind.DatatypeConverter._
import com.keepit.common.db._
import com.keepit.common.logging.Logging

class DuplicateDocumentDetection(documentSignatures: Seq[(Id[NormalizedURI], Array[Byte])]) extends Logging {

  val DEFAULT_THRESHOLD = 0.90
  val EMPTY_DOCUMENT = (new SignatureBuilder).add("").build().bytes

  // from `Signature`, duplicated to use Array[Byte]s for efficiency
  def similarTo(that: Array[Byte], other: Array[Byte]): Double = {
    if (that.length == other.length) {
      that.zip(other).filter{ pair => pair._1 == pair._2 }.size.toDouble / 100.0d
    } else {
      0.0d
    }
  }

  def processDocument(current: (Id[NormalizedURI], Array[Byte]), threshold: Double = DEFAULT_THRESHOLD) = {
    documentSignatures.map { case (otherId, otherSig) =>
      val gt = current._1.id >= otherId.id
      if (gt) {
        None
      }
      else {
        val s = similarTo(current._2, otherSig)
        if(s >= threshold &&
          otherSig.deep != EMPTY_DOCUMENT.deep) {
          Some((otherId, s))
        } else {
          None
        }
      }
    }.flatten
  }

  def processDocuments(threshold: Double = DEFAULT_THRESHOLD) = {
    documentSignatures.flatMap { ds =>
      processDocument(ds, threshold) match {
        case Nil => None
        case result => Some((ds._1,result))
      }
    }
  }
}
