package com.keepit.scraper

import com.keepit.model.{NormalizedURI, ScrapeInfo}
import com.keepit.common.db.CX
import play.api.Play.current
import javax.xml.bind.DatatypeConverter._
import com.keepit.common.db._

class DuplicateDocumentDetection {
  val DEFAULT_THRESHOLD = 0.90
  val EMPTY_DOCUMENT = (new SignatureBuilder).add("").build().bytes

  lazy val documentSignatures = CX.withConnection { implicit conn =>
    ScrapeInfo.all.map(s => (s.uriId, parseBase64Binary(s.signature)))
  }

  // from `Signature`, duplicated to use Array[Byte]s for efficiency
  def similarTo(that: Array[Byte], other: Array[Byte]): Double = {
    if (that.length == other.length) {
      that.zip(other).filter{ pair => pair._1 == pair._2 }.size.toDouble / 100.0d
    } else {
      0.0d
    }
  }

  def processDocument(current: (Id[NormalizedURI], Array[Byte]), threshold: Double = DEFAULT_THRESHOLD) = {
    documentSignatures.par.map { other =>
      val s = similarTo(current._2, other._2)
      if(s > threshold &&
        other._2.deep != EMPTY_DOCUMENT.deep &&
        other._1 != current._1) {
        Some((other._1, s))
      } else {
        None
      }
    }.flatten.seq
  }

  def processDocuments(threshold: Double = DEFAULT_THRESHOLD) = {
    CX.withConnection { implicit conn =>
      documentSignatures.flatMap(ds =>
        processDocument(ds, threshold) match {
          case Nil => None
          case result => Some((ds._1,result))
        }
      )
    }
  }
}
