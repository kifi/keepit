package com.keepit.model

import org.specs2.mutable._

import com.keepit.test._
import scala.Some
import com.keepit.normalizer.NormalizationService
import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RSession

class KeepToCollectionTest  extends Specification with ShoeboxTestInjector {

  val hover = BookmarkSource.keeper
  val initLoad = BookmarkSource.bookmarkImport
  def prenormalize(url: String)(implicit injector: Injector, session: RSession): String = inject[NormalizationService].prenormalize(url).get

  "KeepToCollectionTest " should {
    "load uris from db" in {
      withDb() { implicit injector =>

        val (bookmark1, bookmark2, collections) = db.readWrite {implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C"))
          val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))
          val uri3 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/2"), Some("Amazon1")))
          val uri4 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/3"), Some("Amazon2")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))
          val url4 = urlRepo.save(URLFactory(url = uri4.url, normalizedUriId = uri4.id.get))

          val bookmark1 = keepRepo.save(Bookmark(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
            uriId = uri1.id.get, source = BookmarkSource.keeper, state = BookmarkStates.ACTIVE))
          val bookmark2 = keepRepo.save(Bookmark(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id,
            uriId = uri2.id.get, source = BookmarkSource.keeper, state = BookmarkStates.ACTIVE))
          val bookmark3 = keepRepo.save(Bookmark(title = Some("C1"), userId = user1.id.get, url = url3.url, urlId = url3.id,
            uriId = uri3.id.get, source = BookmarkSource.keeper, state = BookmarkStates.ACTIVE))
          keepRepo.save(Bookmark(title = Some("D1"), userId = user1.id.get, url = url4.url, urlId = url4.id,
            uriId = uri4.id.get, source = BookmarkSource.keeper, state = BookmarkStates.ACTIVE))

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
                            collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
                            collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
                            Nil
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = collections(0).id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark2.id.get, collectionId = collections(0).id.get))

          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark2.id.get, collectionId = collections(1).id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark2.id.get, collectionId = collections(2).id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark3.id.get, collectionId = collections(1).id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark3.id.get, collectionId = collections(2).id.get))
          (bookmark1, bookmark2, collections)
        }

        db.readOnly { implicit s =>
          val uris = keepToCollectionRepo.getUriIdsInCollection(collections(0).id.get)
          uris.length === 2
          uris === Seq(BookmarkUriAndTime(bookmark1.uriId, bookmark1.createdAt), BookmarkUriAndTime(bookmark2.uriId, bookmark2.createdAt))
        }
      }
    }
  }
}
