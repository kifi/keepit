package com.keepit.scraper

import com.keepit.model._
import javax.xml.bind.DatatypeConverter._
import com.keepit.common.logging.Logging
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import play.api.libs.concurrent.Akka
import com.keepit.common.mail.{EmailAddresses, ElectronicMail, PostOffice}
import play.api.Play.current
import com.google.inject.Inject

class DuplicateDocumentDetection @Inject() (
    db: Database,
    scrapeInfoRepo: ScrapeInfoRepo,
    dupeRepo: DuplicateDocumentRepo,
    postOffice: PostOffice)
  extends Logging {

  // These will be removed when the unscrapeable documents are reprocessed
  val IGNORED_DOCUMENTS: Set[String] = Set(/*
    "5XPalcuVz83xN6gTChVzNBWyFrm3Cfin8DQgqPQtZSIsIQg3Xz4n1v51KdvVEo0Pk9pGA3L7WTxNrgc/CzCaZ7S2YXAY/XkcWe1NIE0drQYstWxQpYZpHJaZWPJQVbANQ1CJiA==", // Gmail
    "kxZCPEHXuSaxCePUNWG60BjuZykQI9SPBTuYD6L3EFh6m0gfCsHZ9CUK3vf8w9h9KNhfFwX0/c7tMCi2Dk+rFo6q345kuMW28SQf/Eghowe2hLtrUuLdXR6JnfhipjZOh8CWhQ==", // Facebook
    "F1YwNyMtApK7kNCx+rbBrFYA0bZUfYjLwWGuAElDRSa0QFGRuYWo2XUmKKv1cLxk6lsm7iSeb4YoafJUkdaA7jNhuzvWO139f2Y1QllHlDVFa4SoruDiMeBTyd/dOsgxu8OMmw==", // Google groups
    "Rn22yRvfiGeHlbEiaE0A4+q38BsEGqmGYmpArdUNrAit5WXGxjAFLEiZE24/7WRX+b4JXq4IV1TAPSJ1BjoHe55q7HaJiVPvnlKLHn6TboNVwqcUCMvD9l9g6HpZ/+EhwnUeAQ==", // Google drive
    "vbZhGbcWZEQaXBE7pR/Ofr7ggs9cJh3bjcdoLT0mtdxpNi/4cOhIKDfYt8dNST6BGkNwc+WlDx4IMpr+X146sRajcZgUKozX9UXW2lmgkJYJZQVv5jvWCJZCZo8PAJfqKD60cQ==", // Google docs
    "JDbFvPSKTRlPsoKDyJg4JGGupPPFzMVfEs2nGu2J+TKPqJgJPDlIshLdgpU8LbthPjU9+w3wGrZD+aQGpNksEXAcVehY463O30R29FEcBxMI/69q9NqZUqljQihtC3d8sDMqJA==", // Google docs
    "dcpgM9N93t/5t4qXNFdu7N6I0TXSiYF7CslQGxTsislHL5SkH9eEnupZEeTBUCxwBHhqLuU7miNW6/kn4zGO+9QSN8qj0r3nNwtInWbWsshka7J/lbte/fXLk99hWfGbxVZOtg==", // Asana
    "406AgpatC4kT6qGqYgIm9fmn07G23tFxs+nFJfLlZCOMhzQ6OeXxz9oq8OnUu9uaNTAtLDr1aqikkXPWxgsgy4DlwHa9kvZI6TXRVvJUnkIFxn6ZE7eAQhWDtM4zkCjJ4hCEtA==", // Juniper
    "wz+QbFgpl+4jhr2Li8kDbWGjqmxH9gu60g33fhq2u4EDWGUHW7PW2XSBoRfP8JYQ8UpahZAOfh3W5Mjl03AMgWNediM+1HQTKtPknY8lHf7IE20TQpH6H/gWQLS58nQKmNoeEg==", // LinkedIn
    "AI3rIinEkzdH3np/61CIsuYfekOUMfjwnL+3fYCAwTi8BASzyLY3OiBmttJ8Y9LZBVoc4wcZZWncioDrNKwbcr1RK/Jg/0r71Bwq5164/B5fzBmNIqa8CQL1cMO6w/UX4h28PA==", // Google docs
    "4k3jinEqzd67np/61CIsuYfehiUCvjwaL+3feeAe+OyBKmzWX9pOiBmttIu6uSSBVocxwdSxGnc4w6xO8cbPxL2K/lrNxL71Bwq51m4/B5fvhmNIhe81wb1cMO6w/Xo4mq8PA==" // Google docs
  */)

  lazy val documentSignatures = db.readOnly { implicit session =>
    scrapeInfoRepo.allActive.map { s =>
      if (IGNORED_DOCUMENTS.contains(s.signature)) None
      else Some((s.uriId, parseBase64Binary(s.signature)))
    }.flatten
  }

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

  def alreadyDetected(nuriId: Id[NormalizedURI])(implicit session: RSession) = {
    dupeRepo.getSimilarTo(nuriId).filter(_.state != DuplicateDocumentStates.NEW).nonEmpty
  }

  def processDocument(currentDoc: (Id[NormalizedURI], Array[Byte]), threshold: Double) = {
    db.readOnly { implicit session =>
      documentSignatures.map { case (otherId, otherSig) =>
        if (currentDoc._1.id >= otherId.id) {
          None
        } else {
          val s = similarTo(currentDoc._2, otherSig)
          if(s >= threshold &&
            otherSig.deep != EMPTY_DOCUMENT.deep &&
            !alreadyDetected(otherId)) {
            Some((otherId, s))
          } else {
            None
          }
        }
      }.flatten
    }
  }

  def findDupeDocuments(threshold: Double = DEFAULT_THRESHOLD): Seq[(Id[NormalizedURI], Seq[(Id[NormalizedURI], Double)])] = {
    documentSignatures.flatMap { ds =>
      processDocument(ds, threshold) match {
        case Nil => None
        case result => Some((ds._1,result))
      }
    }
  }

 def asyncProcessDocuments() = {
   Akka.future {
     val startTime = System.currentTimeMillis

     val docs = findDupeDocuments()

     var dupeDocumentsCount = 0
     db.readWrite { implicit conn =>
       docs.foreach { similarDoc =>
         val id = similarDoc._1
         val similars = similarDoc._2
         similars.foreach { case (otherId, percentMatch) =>
           if(dupeRepo.getSimilarTo(otherId).isEmpty) {
             val dupeDoc = DuplicateDocument(uri1Id = id, uri2Id = otherId, percentMatch = percentMatch)
             dupeRepo.save(dupeDoc)
             dupeDocumentsCount += 1
           }
         }
       }
     }

     val elapsedTimeMs = System.currentTimeMillis - startTime
     val result = "Runtime: %sms, Dupes found: %s. See admin panel for details.".format(elapsedTimeMs, dupeDocumentsCount)

     val toAddr = if (play.api.Play.isDev) EmailAddresses.ANDREW else EmailAddresses.ENG
     db.readWrite { implicit s =>
       postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(toAddr), subject = "Duplication Report",
        htmlBody = result, category = PostOffice.Categories.ADMIN))
     }
   }
 }


}
