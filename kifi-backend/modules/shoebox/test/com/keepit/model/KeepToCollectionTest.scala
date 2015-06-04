package com.keepit.model

import org.specs2.mutable._

import com.keepit.test._
import scala.Some
import com.keepit.normalizer.NormalizationService
import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.model.UserFactoryHelper._

class KeepToCollectionTest extends Specification with ShoeboxTestInjector {

  val hover = KeepSource.keeper
  val initLoad = KeepSource.bookmarkImport
  def prenormalize(url: String)(implicit injector: Injector): String = inject[NormalizationService].prenormalize(url).get

  "KeepToCollectionTest " should {
    "load uris from db" in {
      withDb() { implicit injector =>

        val (bookmark1, bookmark2, collections) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("Andrew", "C").withUsername("test").saved
          val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))
          val uri3 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/2"), Some("Amazon1")))
          val uri4 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/3"), Some("Amazon2")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))
          val url4 = urlRepo.save(URLFactory(url = uri4.url, normalizedUriId = uri4.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, state = KeepStates.ACTIVE, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
          val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = KeepSource.keeper, state = KeepStates.ACTIVE, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
          val bookmark3 = keepRepo.save(Keep(title = Some("C1"), userId = user1.id.get, url = url3.url, urlId = url3.id.get,
            uriId = uri3.id.get, source = KeepSource.keeper, state = KeepStates.ACTIVE, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
          keepRepo.save(Keep(title = Some("D1"), userId = user1.id.get, url = url4.url, urlId = url4.id.get,
            uriId = uri4.id.get, source = KeepSource.keeper, state = KeepStates.ACTIVE, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction1"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction2"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction3"))) ::
            Nil
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = collections(0).id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark2.id.get, collectionId = collections(0).id.get))

          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark2.id.get, collectionId = collections(1).id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark2.id.get, collectionId = collections(2).id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark3.id.get, collectionId = collections(1).id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark3.id.get, collectionId = collections(2).id.get))
          (bookmark1, bookmark2, collections)
        }

        db.readOnlyMaster { implicit s =>
          val uris = keepToCollectionRepo.getUriIdsInCollection(collections(0).id.get)
          uris.length === 2
          uris === Seq(KeepUriAndTime(bookmark1.uriId, bookmark1.createdAt), KeepUriAndTime(bookmark2.uriId, bookmark2.createdAt))
        }
      }
    }
  }
}
