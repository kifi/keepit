package com.keepit.controllers.admin

import play.api.data._
import java.util.concurrent.TimeUnit
import play.api._
import libs.concurrent.Akka
import play.api.Play.current
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.controllers.CommonActions._
import com.keepit.inject._
import com.keepit.scraper._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.ArticleStore
import com.keepit.common.controller.FortyTwoController
import javax.xml.bind.DatatypeConverter._
import com.keepit.common.mail._
import slick.DBConnection

object ScraperController extends FortyTwoController {

  def scrape = AdminHtmlAction { implicit request =>
    val scraper = inject[ScraperPlugin]
    val articles = scraper.scrape()
    Ok(views.html.scrape(articles))
  }

  // These will be removed when the unscrapeable documents are reprocessed
  val IGNORED_DOCUMENTS = Set(
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
  )

  def duplicateDocumentDetection = AdminHtmlAction { implicit request =>

    Akka.future {
      val startTime = System.currentTimeMillis
      val documentSignatures = CX.withConnection { implicit conn =>
        ScrapeInfoCxRepo.allActive.map { s =>
          if (IGNORED_DOCUMENTS.contains(s.signature)) None
          else Some((s.uriId, parseBase64Binary(s.signature)))
        }.flatten
      }

      val dupe = new DuplicateDocumentDetection(documentSignatures)
      val docs = dupe.processDocuments()

      val elapsedTimeMs = System.currentTimeMillis - startTime

      val dupeDocumentsCount = docs.size
      val dupeRepo = inject[DuplicateDocumentRepo]
      var resultStr = new StringBuilder

      resultStr ++= "Runtime: %sms<br>\n<br>\n<pre>".format(elapsedTimeMs)

      val normURIRepo = inject[NormalizedURIRepo]
      inject[DBConnection].readWrite { implicit conn =>
        docs.map { case(id, similars) =>
          val uri = normURIRepo.get(id)
          resultStr ++= "*\t%s\t*\t%s\n".format(uri.id.getOrElse("x"), uri.url.take(100))
          similars map { case (otherId, percentMatch) =>
            val otherUri = normURIRepo.get(otherId)
            val dupeDoc = DuplicateDocument(uri1Id = id, uri2Id = otherId, percentMatch = percentMatch)
            dupeRepo.save(dupeDoc)
            resultStr ++= "\t%s\t%s\t%s\n".format(otherUri.id.getOrElse("x"), percentMatch, otherUri.url.take(100))
          }
        }
      }

      resultStr ++= "</pre>"

      val result = resultStr.toString

      val postOffice = inject[PostOffice]
      postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = "Duplication Report", htmlBody = result, category = PostOffice.Categories.ADMIN))

    }
    Ok("Dupe logging started. Expect an email :)")
  }

  def scrapeByState(state: State[NormalizedURI]) = AdminHtmlAction { implicit request =>
    transitionByAdmin(state -> Set(ACTIVE)) { newState =>
      CX.withConnection { implicit c =>
        NormalizedURICxRepo.getByState(state).foreach{ uri => uri.withState(newState).save }
      }
      val scraper = inject[ScraperPlugin]
      val articles = scraper.scrape()
      Ok(views.html.scrape(articles))
    }
  }

  def getScraped(id: Id[NormalizedURI]) = AdminHtmlAction { implicit request =>
    val store = inject[ArticleStore]
    val article = store.get(id).get
    val uri = CX.withConnection { implicit c =>
      NormalizedURICxRepo.get(article.id)
    }
    Ok(views.html.article(article, uri))
  }

  def getUnscrapable() = AdminHtmlAction { implicit request =>
    val docs = inject[DBConnection].readOnly { implicit conn =>
      inject[UnscrapableRepo].allActive()
    }

    Ok(views.html.unscrapable(docs))
  }

  def createUnscrapable() = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("No form data given.")
    }
    val pattern = form.get("pattern").get
    inject[DBConnection].readWrite { implicit conn =>
      inject[UnscrapableRepo].save(Unscrapable(pattern = pattern))
    }
    Redirect(com.keepit.controllers.admin.routes.ScraperController.getUnscrapable())
  }
}

