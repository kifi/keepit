package com.keepit.classify

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

import com.coremedia.iso.Hex.encodeHex
import com.google.inject.{Inject, ImplementedBy}
import com.keepit.common.db.slick.DBConnection
import com.keepit.common.net.HttpClient

import akka.actor.{Actor, Props, ActorSystem}
import akka.dispatch.Future
import akka.pattern.ask
import akka.util.duration._


private case class FetchDomainInfo(domain: String)

private[classify] class DomainClassificationActor(db: DBConnection, client: HttpClient, updater: SensitivityUpdater,
    domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo) extends Actor {

  private final val KEY = "42go42"

  private def getTagNames(url: String): Seq[DomainTagName] = {
    // see http://www.komodia.com/wiki/index.php/URL_server_protocol
    val md = MessageDigest.getInstance("MD5")
    val guid = UUID.randomUUID.toString.toUpperCase
    val id = encodeHex(md.digest((KEY + guid + KEY).getBytes("UTF-8"))).toLowerCase
    val encodedUrl = URLEncoder.encode(url, "UTF-8")
    val result =
      client.get("http://thor.komodia.com/url.php?version=w11&guid=" + guid + "&id=" + id + "&url=" + encodedUrl).body
    result.split("~", 2).toList match {
      case ("FM" | "FR") :: tagString :: Nil =>
        // response is comma separated, but includes commas inside parentheses
        """\(([^)]*)\)""".r.replaceAllIn(tagString, _.group(0).replace(',', '/'))
          .split(",")
          .map(_.replace('/', ','))
          .map(DomainTagName(_))
          .toSeq
      case _ => Seq()
    }
  }

  protected def receive = {
    case FetchDomainInfo(hostname) =>
      val tagNames = getTagNames(hostname)
      db.readWrite { implicit s =>
        val domain = domainRepo.get(hostname, excludeState = None) match {
          case Some(d) if d.state != DomainStates.ACTIVE => domainRepo.save(d.withState(DomainStates.ACTIVE))
          case Some(d) => d
          case None => domainRepo.save(Domain(hostname = hostname))
        }
        val tagIds = tagNames.map { name =>
          (tagRepo.get(name, excludeState = None) match {
            case Some(tag) if tag.state != DomainTagStates.ACTIVE => tagRepo.save(tag.withState(DomainTagStates.ACTIVE))
            case Some(tag) => tag
            case None => tagRepo.save(DomainTag(name = name))
          }).id.get
        }.toSet
        val existingTagRelationships = domainToTagRepo.getByDomain(domain.id.get, excludeState = None)
        for (r <- existingTagRelationships if r.state != DomainTagStates.ACTIVE) {
          domainToTagRepo.save(r.withState(DomainToTagStates.ACTIVE))
        }
        domainToTagRepo.insertAll((tagIds -- existingTagRelationships.map(_.tagId)).map { tagId =>
          DomainToTag(domainId = domain.id.get, tagId = tagId)
        }.toSeq)
        sender ! updater.updateSensitivity(Seq(domain)).head.sensitive
      }
  }
}

@ImplementedBy(classOf[DomainClassifierImpl])
trait DomainClassifier {
  def isSensitive(domain: String): Either[Future[Option[Boolean]], Option[Boolean]]
}

class DomainClassifierImpl @Inject()(system: ActorSystem, db: DBConnection, client: HttpClient,
    updater: SensitivityUpdater, domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo)
    extends DomainClassifier {

  private val actor = system.actorOf(Props {
    new DomainClassificationActor(db, client, updater, domainRepo, tagRepo, domainToTagRepo)
  })

  def isSensitive(domainName: String): Either[Future[Option[Boolean]], Option[Boolean]] = {
    db.readOnly { implicit s =>
      domainRepo.get(domainName) match {
        case Some(domain) => Right(domain.sensitive)
        case None => Left(actor.ask(FetchDomainInfo(domainName))(5 seconds).mapTo[Option[Boolean]])
      }
    }
  }
}
